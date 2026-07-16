// @vitest-environment node

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  ConsoleAuthRecordCodec,
  ConsoleAuthRecordCodecError,
} from "@/server/auth/ConsoleAuthRecordCodec";
import type {
  PendingOidcAuthorization,
  StoredConsoleSession,
} from "@/server/auth/ConsoleAuthStore";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";

const OLD_KEY = Buffer.alloc(32, 7).toString("base64url");
const NEW_KEY = Buffer.alloc(32, 9).toString("base64url");

describe("encrypted shared authentication record codec", () => {
  it("round-trips bounded records without exposing tokens and authenticates record identity", () => {
    const codec = new ConsoleAuthRecordCodec([{ id: "key_old", key: OLD_KEY }]);
    const authorization = authorizationRecord();
    const session = sessionRecord();
    const first = codec.encodeAuthorization(authorization);
    const second = codec.encodeAuthorization(authorization);
    const sessionWire = codec.encodeSession(session);

    expect(first).not.toBe(second);
    expect(first).toMatch(/^v2\./u);
    expect(first).not.toContain(authorization.pkceVerifier);
    expect(sessionWire).not.toContain(session.accessToken);
    expect(codec.decodeAuthorization(first, authorization.stateDigest)).toEqual(authorization);
    expect(codec.decodeSession(sessionWire, session.sessionIdDigest)).toEqual(session);
    expect(() => codec.decodeAuthorization(first, sha256Base64Url("other")))
      .toThrow(ConsoleAuthRecordCodecError);
    expect(() => codec.decodeAuthorization(sessionWire, session.sessionIdDigest))
      .toThrow(ConsoleAuthRecordCodecError);
    expect(() => codec.decodeAuthorization(first.replace(/^v2\./u, "v1."), authorization.stateDigest))
      .toThrow(ConsoleAuthRecordCodecError);
  });

  it("detects ciphertext tampering and supports decrypt-only rotation keys", () => {
    const oldCodec = new ConsoleAuthRecordCodec([{ id: "key_old", key: OLD_KEY }]);
    const rotatedCodec = new ConsoleAuthRecordCodec([
      { id: "key_new", key: NEW_KEY },
      { id: "key_old", key: OLD_KEY },
    ]);
    const session = sessionRecord();
    const oldWire = oldCodec.encodeSession(session);
    expect(rotatedCodec.decodeSession(oldWire, session.sessionIdDigest)).toEqual(session);
    expect(rotatedCodec.encodeSession(session).split(".")[1]).toBe("key_new");

    const replacement = oldWire.endsWith("A") ? "B" : "A";
    const tampered = `${oldWire.slice(0, -1)}${replacement}`;
    expect(() => rotatedCodec.decodeSession(tampered, session.sessionIdDigest))
      .toThrow(ConsoleAuthRecordCodecError);
  });
});

function authorizationRecord(): PendingOidcAuthorization {
  return Object.freeze({
    stateDigest: sha256Base64Url("state"),
    consoleOriginBindingDigest: "D".repeat(43),
    sourceProfileId: "primary",
    sourceProfileBindingDigest: "B".repeat(43),
    nonce: "nonce-value",
    pkceVerifier: "a".repeat(64),
    redirectUri: "https://console.example/api/auth/oidc/callback",
    returnPath: "/zh",
    createdAtEpochMillis: 1_000,
    expiresAtEpochMillis: 61_000,
  });
}

function sessionRecord(): StoredConsoleSession {
  return Object.freeze({
    sessionIdDigest: sha256Base64Url("session"),
    consoleOriginBindingDigest: "D".repeat(43),
    sourceProfileId: "primary",
    sourceProfileBindingDigest: "B".repeat(43),
    subjectId: "user-1",
    subjectDisplayName: "Alice",
    tenantAlias: "Tianjin",
    accessToken: "opaque-server-token",
    tokenType: "Bearer",
    createdAtEpochMillis: 1_000,
    expiresAtEpochMillis: 61_000,
  });
}
