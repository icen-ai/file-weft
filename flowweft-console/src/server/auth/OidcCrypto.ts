import "server-only";
import { createHash, randomBytes } from "node:crypto";

const PKCE_VERIFIER_BYTES = 64;
const OPAQUE_TOKEN_BYTES = 32;

function base64Url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64url");
}

export function sha256Base64Url(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("base64url");
}

export function createPkceVerifier(): string {
  const verifier = base64Url(randomBytes(PKCE_VERIFIER_BYTES));
  if (verifier.length < 43 || verifier.length > 128) {
    throw new Error("Generated PKCE verifier is outside the RFC 7636 bounds.");
  }
  return verifier;
}

export function createPkceChallenge(verifier: string): string {
  if (!/^[A-Za-z0-9._~-]{43,128}$/u.test(verifier)) {
    throw new Error("PKCE verifier is invalid.");
  }
  return sha256Base64Url(verifier);
}

export function createAuthorizationState(): string {
  return base64Url(randomBytes(OPAQUE_TOKEN_BYTES));
}

export function createOidcNonce(): string {
  return base64Url(randomBytes(OPAQUE_TOKEN_BYTES));
}

export function createSessionId(): string {
  return base64Url(randomBytes(OPAQUE_TOKEN_BYTES));
}
