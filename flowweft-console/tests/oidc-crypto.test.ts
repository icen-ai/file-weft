import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  createAuthorizationState,
  createPkceChallenge,
  createPkceVerifier,
  createSessionId,
  sha256Base64Url,
} from "@/server/auth/OidcCrypto";

describe("OIDC cryptographic material", () => {
  it("matches the RFC 7636 S256 example", () => {
    expect(createPkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
      .toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });

  it("generates high-entropy bounded opaque values", () => {
    const verifier = createPkceVerifier();
    const states = new Set(Array.from({ length: 32 }, () => createAuthorizationState()));
    const sessions = new Set(Array.from({ length: 32 }, () => createSessionId()));

    expect(verifier).toMatch(/^[A-Za-z0-9_-]{86}$/u);
    expect(createPkceChallenge(verifier)).toMatch(/^[A-Za-z0-9_-]{43}$/u);
    expect(states.size).toBe(32);
    expect(sessions.size).toBe(32);
    expect([...states].every((value) => value.length === 43)).toBe(true);
    expect(sha256Base64Url("state")).toHaveLength(43);
  });

  it("rejects an invalid verifier before hashing", () => {
    expect(() => createPkceChallenge("short")).toThrow();
  });
});
