import "server-only";
import type { NextRequest } from "next/server";

export class ConsoleRequestRejectedError extends Error {
  constructor() {
    super("Console request rejected.");
    this.name = "ConsoleRequestRejectedError";
  }
}

export function requireSameOriginMutation(request: NextRequest, publicOrigin: string | null): void {
  if (!publicOrigin || request.headers.get("origin") !== publicOrigin) {
    throw new ConsoleRequestRejectedError();
  }
  const fetchSite = request.headers.get("sec-fetch-site");
  if (fetchSite !== null && fetchSite !== "same-origin") {
    throw new ConsoleRequestRejectedError();
  }
}

export async function readBoundedForm(
  request: NextRequest,
  maximumBytes = 8_192,
): Promise<URLSearchParams> {
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  if (contentType !== "application/x-www-form-urlencoded") {
    throw new ConsoleRequestRejectedError();
  }
  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) {
    throw new ConsoleRequestRejectedError();
  }
  const reader = request.body?.getReader();
  if (!reader) {
    throw new ConsoleRequestRejectedError();
  }
  const chunks: Uint8Array[] = [];
  let received = 0;
  while (true) {
    const part = await reader.read();
    if (part.done) {
      break;
    }
    received += part.value.byteLength;
    if (received > maximumBytes) {
      await reader.cancel();
      throw new ConsoleRequestRejectedError();
    }
    chunks.push(part.value);
  }
  const bytes = new Uint8Array(received);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new ConsoleRequestRejectedError();
  }
  return new URLSearchParams(text);
}

export function requireSingleFormValue(form: URLSearchParams, name: string, maximumLength: number): string {
  const values = form.getAll(name);
  if (values.length !== 1 || values[0] === undefined || values[0].length < 1 ||
    values[0].length > maximumLength || /[\u0000-\u001f\u007f]/u.test(values[0])) {
    throw new ConsoleRequestRejectedError();
  }
  return values[0];
}
