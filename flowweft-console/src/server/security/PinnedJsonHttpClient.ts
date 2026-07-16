import "server-only";
import { lookup } from "node:dns/promises";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { BlockList, isIP } from "node:net";

const MAXIMUM_REQUEST_BYTES = 64 * 1_024;
const DEFAULT_MAXIMUM_RESPONSE_BYTES = 512 * 1_024;
const ABSOLUTE_MAXIMUM_RESPONSE_BYTES = 4 * 1_024 * 1_024;
const DNS_TIMEOUT_MILLIS = 5_000;
const blockedAddresses = createBlockedAddressList();

export interface PinnedJsonRequest {
  readonly url: string;
  readonly method: "GET" | "POST";
  readonly body?: string;
  readonly headers?: Readonly<Record<string, string>>;
  readonly query?: Readonly<Record<string, string>>;
  readonly timeoutMillis: number;
  readonly allowPrivateNetwork: boolean;
  readonly maximumResponseBytes?: number;
}

export class PinnedJsonHttpError extends Error {
  readonly code: "UNSAFE_ENDPOINT" | "DNS_FAILURE" | "UPSTREAM_FAILURE" | "INVALID_RESPONSE";

  constructor(code: PinnedJsonHttpError["code"]) {
    super(`Pinned JSON request failed: ${code}.`);
    this.name = "PinnedJsonHttpError";
    this.code = code;
  }
}

/**
 * Resolves once, rejects mixed public/private answers, and pins the verified address into the
 * socket lookup callback. Redirects are never followed and response bodies are strictly bounded.
 */
export async function requestPinnedJson(request: PinnedJsonRequest): Promise<unknown> {
  const endpoint = requireEndpoint(request.url, request.allowPrivateNetwork);
  applySafeQuery(endpoint, request.query);
  if (!Number.isSafeInteger(request.timeoutMillis) || request.timeoutMillis < 100 || request.timeoutMillis > 30_000) {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  const body = request.body ?? "";
  if (Buffer.byteLength(body, "utf8") > MAXIMUM_REQUEST_BYTES) {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  const maximumResponseBytes = request.maximumResponseBytes ?? DEFAULT_MAXIMUM_RESPONSE_BYTES;
  if (!Number.isSafeInteger(maximumResponseBytes) || maximumResponseBytes < 1 ||
    maximumResponseBytes > ABSOLUTE_MAXIMUM_RESPONSE_BYTES) {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  const customHeaders = request.headers ?? {};
  const customHeaderEntries = Object.entries(customHeaders);
  const normalizedHeaderNames = customHeaderEntries.map(([name]) => name.toLowerCase());
  if (new Set(normalizedHeaderNames).size !== normalizedHeaderNames.length ||
    customHeaderEntries.some(([name, value]) => !isAllowedRequestHeader(name, value))) {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  const addresses = await resolvePinnedAddresses(endpoint.hostname, request.allowPrivateNetwork);
  const selected = addresses[0];
  if (!selected) {
    throw new PinnedJsonHttpError("DNS_FAILURE");
  }

  return await new Promise<unknown>((resolve, reject) => {
    const transport = endpoint.protocol === "https:" ? httpsRequest : httpRequest;
    const headers = Object.freeze({
      Accept: "application/json",
      "Cache-Control": "no-store",
      ...customHeaders,
      ...(body === "" ? {} : { "Content-Length": String(Buffer.byteLength(body, "utf8")) }),
    });
    const upstream = transport(endpoint, {
      method: request.method,
      headers,
      rejectUnauthorized: true,
      family: selected.family,
      lookup: (_hostname, _options, callback) => callback(null, selected.address, selected.family),
    }, (response) => {
      const status = response.statusCode ?? 0;
      const contentType = String(response.headers["content-type"] ?? "").toLowerCase();
      const declaredLength = Number(response.headers["content-length"] ?? 0);
      if (status !== 200) {
        response.resume();
        reject(new PinnedJsonHttpError("UPSTREAM_FAILURE"));
        return;
      }
      if (!contentType.includes("application/json") && !contentType.includes("+json")) {
        response.resume();
        reject(new PinnedJsonHttpError("INVALID_RESPONSE"));
        return;
      }
      if (Number.isFinite(declaredLength) && declaredLength > maximumResponseBytes) {
        response.destroy();
        reject(new PinnedJsonHttpError("INVALID_RESPONSE"));
        return;
      }

      const chunks: Buffer[] = [];
      let received = 0;
      response.on("data", (chunk: Buffer | string) => {
        const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        received += bytes.length;
        if (received > maximumResponseBytes) {
          reject(new PinnedJsonHttpError("INVALID_RESPONSE"));
          response.destroy();
          return;
        }
        chunks.push(bytes);
      });
      response.on("error", () => reject(new PinnedJsonHttpError("UPSTREAM_FAILURE")));
      response.on("end", () => {
        try {
          const text = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks));
          resolve(JSON.parse(text) as unknown);
        } catch {
          reject(new PinnedJsonHttpError("INVALID_RESPONSE"));
        }
      });
    });
    upstream.setTimeout(request.timeoutMillis, () => {
      upstream.destroy(new PinnedJsonHttpError("UPSTREAM_FAILURE"));
    });
    upstream.on("error", () => reject(new PinnedJsonHttpError("UPSTREAM_FAILURE")));
    if (body !== "") {
      upstream.write(body, "utf8");
    }
    upstream.end();
  });
}

function applySafeQuery(endpoint: URL, query: Readonly<Record<string, string>> | undefined): void {
  if (!query) {
    return;
  }
  const entries = Object.entries(query);
  if (entries.length > 16) {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  for (const [name, value] of entries) {
    if (!/^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(name) || value.length > 1_024 ||
      /[\u0000-\u001f\u007f]/u.test(value)) {
      throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
    }
    endpoint.searchParams.set(name, value);
  }
}

function isAllowedRequestHeader(name: string, value: string): boolean {
  const normalizedName = name.toLowerCase();
  if (normalizedName === "content-type") {
    return value === "application/x-www-form-urlencoded" || value === "application/json";
  }
  if (normalizedName === "authorization") {
    return /^Bearer [\x21-\x7e]{1,16384}$/u.test(value);
  }
  return false;
}

export function isBlockedNetworkAddress(address: string, family: 4 | 6): boolean {
  const expectedFamily = family === 4 ? "ipv4" : "ipv6";
  if (isIP(address) !== family) {
    return true;
  }
  if (family === 6 && address.toLowerCase().startsWith("::ffff:")) {
    return true;
  }
  return blockedAddresses.check(address, expectedFamily);
}

async function resolvePinnedAddresses(hostname: string, allowPrivateNetwork: boolean) {
  let addresses: Array<{ address: string; family: 4 | 6 }>;
  try {
    addresses = await withTimeout(
      lookup(hostname, { all: true, verbatim: true }),
      DNS_TIMEOUT_MILLIS,
    ) as Array<{ address: string; family: 4 | 6 }>;
  } catch {
    throw new PinnedJsonHttpError("DNS_FAILURE");
  }
  if (addresses.length < 1 || addresses.length > 32 ||
    !allowPrivateNetwork && addresses.some((entry) => isBlockedNetworkAddress(entry.address, entry.family))) {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  return addresses;
}

function requireEndpoint(raw: string, allowPrivateNetwork: boolean): URL {
  let endpoint: URL;
  try {
    endpoint = new URL(raw);
  } catch {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  if (endpoint.protocol !== "https:" && !(allowPrivateNetwork && endpoint.protocol === "http:") ||
    endpoint.username !== "" || endpoint.password !== "" || endpoint.search !== "" || endpoint.hash !== "") {
    throw new PinnedJsonHttpError("UNSAFE_ENDPOINT");
  }
  return endpoint;
}

async function withTimeout<T>(operation: Promise<T>, timeoutMillis: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<T>((_resolve, reject) => {
        timer = setTimeout(() => reject(new PinnedJsonHttpError("DNS_FAILURE")), timeoutMillis);
      }),
    ]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
}

function createBlockedAddressList(): BlockList {
  const list = new BlockList();
  [
    ["0.0.0.0", 8],
    ["10.0.0.0", 8],
    ["100.64.0.0", 10],
    ["127.0.0.0", 8],
    ["169.254.0.0", 16],
    ["172.16.0.0", 12],
    ["192.0.0.0", 24],
    ["192.0.2.0", 24],
    ["192.88.99.0", 24],
    ["192.168.0.0", 16],
    ["198.18.0.0", 15],
    ["198.51.100.0", 24],
    ["203.0.113.0", 24],
    ["224.0.0.0", 4],
    ["240.0.0.0", 4],
  ].forEach(([address, prefix]) => list.addSubnet(String(address), Number(prefix), "ipv4"));
  [
    ["::", 128],
    ["::1", 128],
    ["fc00::", 7],
    ["fe80::", 10],
    ["ff00::", 8],
    ["2001:db8::", 32],
    ["2001:10::", 28],
    ["2001:20::", 28],
    ["2002::", 16],
  ].forEach(([address, prefix]) => list.addSubnet(String(address), Number(prefix), "ipv6"));
  return list;
}
