import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  ConsoleAuthStoreCapacityError,
  InMemoryConsoleAuthStore,
  type PendingOidcAuthorization,
  type StoredConsoleSession,
} from "@/server/auth/ConsoleAuthStore";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";

describe("bounded Console authentication store", () => {
  it("consumes an authorization exactly once and rejects expiry", async () => {
    const store = new InMemoryConsoleAuthStore();
    const active = authorization("active", 100, 200);
    const expired = authorization("expired", 100, 150);
    await store.createAuthorization(active);
    await store.createAuthorization(expired);

    expect(await store.consumeAuthorization(active.stateDigest, 150)).toEqual(active);
    expect(await store.consumeAuthorization(active.stateDigest, 150)).toBeNull();
    expect(await store.consumeAuthorization(expired.stateDigest, 150)).toBeNull();
  });

  it("bounds live records but purges expired capacity first", async () => {
    const store = new InMemoryConsoleAuthStore({ maximumAuthorizations: 1, maximumSessions: 1 });
    await store.createAuthorization(authorization("one", 100, 120));
    await store.createAuthorization(authorization("two", 120, 180));
    await expect(store.createAuthorization(authorization("three", 121, 181)))
      .rejects.toThrow(ConsoleAuthStoreCapacityError);
  });

  it("keeps bearer material server-side and supports expiry and revocation", async () => {
    const store = new InMemoryConsoleAuthStore();
    const record = session("session-one", 100, 200);
    await store.createSession(record);

    expect((await store.readSession(record.sessionIdDigest, 150))?.accessToken).toBe("opaque.access.token");
    await store.revokeSession(record.sessionIdDigest);
    expect(await store.readSession(record.sessionIdDigest, 150)).toBeNull();

    const expiring = session("session-two", 100, 200);
    await store.createSession(expiring);
    expect(await store.readSession(expiring.sessionIdDigest, 200)).toBeNull();
  });

  it("rejects invalid lifetimes and token shapes", async () => {
    const store = new InMemoryConsoleAuthStore();
    await expect(store.createAuthorization({ ...authorization("bad", 100, 200), pkceVerifier: "short" }))
      .rejects.toThrow();
    await expect(store.createSession({ ...session("bad-session", 100, 200), tokenType: "bearer" as "Bearer" }))
      .rejects.toThrow();
  });
});

function authorization(key: string, createdAt: number, expiresAt: number): PendingOidcAuthorization {
  return Object.freeze({
    stateDigest: sha256Base64Url(key),
    sourceProfileId: "primary",
    nonce: "nonce-value",
    pkceVerifier: "a".repeat(64),
    redirectUri: "https://console.example/api/auth/oidc/callback",
    returnPath: "/zh",
    createdAtEpochMillis: createdAt,
    expiresAtEpochMillis: expiresAt,
  });
}

function session(key: string, createdAt: number, expiresAt: number): StoredConsoleSession {
  return Object.freeze({
    sessionIdDigest: sha256Base64Url(key),
    sourceProfileId: "primary",
    subjectId: "user-1",
    subjectDisplayName: "王小明",
    tenantAlias: "天津水务",
    accessToken: "opaque.access.token",
    tokenType: "Bearer",
    createdAtEpochMillis: createdAt,
    expiresAtEpochMillis: expiresAt,
  });
}
