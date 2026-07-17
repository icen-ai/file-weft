// @vitest-environment node

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  ConsoleLoginRateLimitedError,
  InMemoryConsoleLoginAttemptLimiter,
} from "@/server/auth/LoginAttemptLimiter";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";

describe("bounded Console login attempt limiter", () => {
  it("blocks the next attempt within the window and permits it after expiry", () => {
    const limiter = new InMemoryConsoleLoginAttemptLimiter({
      maximumAttempts: 2,
      windowMillis: 1_000,
    });
    const identity = sha256Base64Url("identity");
    limiter.acquire(identity, 1_000);
    limiter.acquire(identity, 1_001);
    expect(() => limiter.acquire(identity, 1_002)).toThrow(ConsoleLoginRateLimitedError);
    expect(() => limiter.acquire(identity, 2_000)).not.toThrow();
    limiter.clear(identity);
    expect(() => limiter.acquire(identity, 2_001)).not.toThrow();
  });
});
