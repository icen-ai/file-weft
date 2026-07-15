import { describe, expect, it, vi } from "vitest";
import { createServer } from "node:http";

vi.mock("server-only", () => ({}));

import {
  isBlockedNetworkAddress,
  PinnedJsonHttpError,
  requestPinnedJson,
} from "@/server/security/PinnedJsonHttpClient";

describe("OIDC pinned network policy", () => {
  it.each([
    ["127.0.0.1", 4],
    ["10.1.2.3", 4],
    ["169.254.169.254", 4],
    ["192.168.1.8", 4],
    ["198.51.100.2", 4],
    ["::1", 6],
    ["fc00::1", 6],
    ["fe80::1", 6],
    ["2001:db8::1", 6],
    ["::ffff:127.0.0.1", 6],
  ] as const)("blocks non-public address %s", (address, family) => {
    expect(isBlockedNetworkAddress(address, family)).toBe(true);
  });

  it.each([
    ["8.8.8.8", 4],
    ["1.1.1.1", 4],
    ["2606:4700:4700::1111", 6],
  ] as const)("allows globally routed address %s", (address, family) => {
    expect(isBlockedNetworkAddress(address, family)).toBe(false);
  });

  it("rejects a mismatched address family", () => {
    expect(isBlockedNetworkAddress("127.0.0.1", 6)).toBe(true);
  });

  it("constructs reviewed query parameters and a Bearer header only after pinning the endpoint", async () => {
    let observedUrl = "";
    let observedAuthorization = "";
    const server = createServer((request, response) => {
      observedUrl = request.url ?? "";
      observedAuthorization = String(request.headers.authorization ?? "");
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"ok":true}');
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      const address = server.address();
      if (!address || typeof address === "string") {
        throw new Error("test server did not expose a TCP port");
      }
      await expect(requestPinnedJson({
        url: `http://127.0.0.1:${address.port}/fileweft/v1/documents`,
        method: "GET",
        query: { cursor: "opaque+/cursor", limit: "25" },
        headers: { Authorization: "Bearer server-only-token" },
        timeoutMillis: 2_000,
        allowPrivateNetwork: true,
      })).resolves.toEqual({ ok: true });
      expect(observedUrl).toBe("/fileweft/v1/documents?cursor=opaque%2B%2Fcursor&limit=25");
      expect(observedAuthorization).toBe("Bearer server-only-token");
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    }
  });

  it("rejects duplicate or unreviewed headers before making a request", async () => {
    await expect(requestPinnedJson({
      url: "https://example.com/fixed",
      method: "GET",
      headers: { Authorization: "Bearer one", authorization: "Bearer two" },
      timeoutMillis: 2_000,
      allowPrivateNetwork: false,
    })).rejects.toBeInstanceOf(PinnedJsonHttpError);
  });
});
