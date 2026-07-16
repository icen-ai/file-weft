// @vitest-environment node

import { createClient } from "redis";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  ConsoleAuthStoreCapacityError,
  type PendingOidcAuthorization,
  type StoredConsoleSession,
} from "@/server/auth/ConsoleAuthStore";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";
import { RedisConsoleAuthStore } from "@/server/auth/RedisConsoleAuthStore";

const redisUrl = process.env.FLOWWEFT_CONSOLE_TEST_REDIS_URL;
const externalIt = redisUrl ? it : it.skip;

describe("shared encrypted Redis Console authentication store", () => {
  externalIt("shares one-time state and encrypted bounded sessions across store instances", async () => {
    const now = Date.now();
    const prefix = `flowweft:console:test:${process.pid}`;
    const definition = {
      url: String(redisUrl),
      keyPrefix: prefix,
      encryptionKeys: [{ id: "test_key", key: Buffer.alloc(32, 21).toString("base64url") }],
    } as const;
    const first = new RedisConsoleAuthStore(definition, 2, 1);
    const second = new RedisConsoleAuthStore(definition, 2, 1);
    const inspector = createClient({ url: String(redisUrl) });
    inspector.on("error", () => {});
    try {
      await expect(first.checkAvailability()).resolves.toBeUndefined();
      const authorization = authorizationRecord(now);
      await first.createAuthorization(authorization);
      await expect(second.consumeAuthorization(authorization.stateDigest, now + 1))
        .resolves.toEqual(authorization);
      await expect(first.consumeAuthorization(authorization.stateDigest, now + 2))
        .resolves.toBeNull();

      const active = sessionRecord("active", now);
      const next = sessionRecord("next", now);
      await first.createSession(active);
      await inspector.connect();
      const raw = await inspector.get(`${prefix}:{session}:record:${active.sessionIdDigest}`);
      expect(raw).toMatch(/^v2\.test_key\./u);
      expect(raw).not.toContain(active.accessToken);
      await expect(second.createSession(next)).rejects.toBeInstanceOf(ConsoleAuthStoreCapacityError);
      await expect(second.readSession(active.sessionIdDigest, now + 1)).resolves.toEqual(active);
      await second.revokeSession(active.sessionIdDigest);
      await expect(first.readSession(active.sessionIdDigest, now + 2)).resolves.toBeNull();
      await second.createSession(next);
      await first.revokeSession(next.sessionIdDigest);
    } finally {
      if (inspector.isOpen) {
        await inspector.quit();
      }
      await Promise.all([first.close(), second.close()]);
    }
  });
});

function authorizationRecord(now: number): PendingOidcAuthorization {
  return Object.freeze({
    stateDigest: sha256Base64Url("redis-state"),
    consoleOriginBindingDigest: "D".repeat(43),
    sourceProfileId: "primary",
    sourceProfileBindingDigest: "B".repeat(43),
    nonce: "nonce-value",
    pkceVerifier: "a".repeat(64),
    redirectUri: "https://console.example/api/auth/oidc/callback",
    returnPath: "/zh",
    createdAtEpochMillis: now,
    expiresAtEpochMillis: now + 60_000,
  });
}

function sessionRecord(key: string, now: number): StoredConsoleSession {
  return Object.freeze({
    sessionIdDigest: sha256Base64Url(key),
    consoleOriginBindingDigest: "D".repeat(43),
    sourceProfileId: "primary",
    sourceProfileBindingDigest: "B".repeat(43),
    subjectId: `user-${key}`,
    subjectDisplayName: "Alice",
    tenantAlias: "Tianjin",
    accessToken: `opaque-token-${key}`,
    tokenType: "Bearer",
    createdAtEpochMillis: now,
    expiresAtEpochMillis: now + 60_000,
  });
}
