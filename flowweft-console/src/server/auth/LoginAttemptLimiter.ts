import "server-only";

import { ConsoleAuthStoreCapacityError } from "@/server/auth/ConsoleAuthStore";

export class ConsoleLoginRateLimitedError extends Error {
  constructor() {
    super("The Console login attempt limit was reached.");
    this.name = "ConsoleLoginRateLimitedError";
  }
}

export interface ConsoleLoginAttemptLimiter {
  acquire(identityDigest: string, nowEpochMillis: number): void;
  clear(identityDigest: string): void;
}

export interface InMemoryConsoleLoginAttemptLimiterOptions {
  readonly maximumEntries?: number;
  readonly maximumAttempts?: number;
  readonly windowMillis?: number;
}

/**
 * Bounded defense-in-depth limiter for the compatibility login path. The
 * upstream host must still enforce its own distributed account/IP policy.
 */
export class InMemoryConsoleLoginAttemptLimiter implements ConsoleLoginAttemptLimiter {
  private readonly attempts = new Map<string, { count: number; expiresAtEpochMillis: number }>();
  private readonly maximumEntries: number;
  private readonly maximumAttempts: number;
  private readonly windowMillis: number;

  constructor(options: InMemoryConsoleLoginAttemptLimiterOptions = {}) {
    this.maximumEntries = requireBoundedInteger(options.maximumEntries ?? 10_000, 1, 100_000);
    this.maximumAttempts = requireBoundedInteger(options.maximumAttempts ?? 5, 1, 100);
    this.windowMillis = requireBoundedInteger(options.windowMillis ?? 60_000, 1_000, 60 * 60_000);
  }

  acquire(identityDigest: string, nowEpochMillis: number): void {
    requireDigest(identityDigest);
    requireTime(nowEpochMillis);
    this.purgeExpired(nowEpochMillis);
    const current = this.attempts.get(identityDigest);
    if (current && current.expiresAtEpochMillis > nowEpochMillis) {
      if (current.count >= this.maximumAttempts) {
        throw new ConsoleLoginRateLimitedError();
      }
      this.attempts.set(identityDigest, Object.freeze({
        count: current.count + 1,
        expiresAtEpochMillis: current.expiresAtEpochMillis,
      }));
      return;
    }
    if (this.attempts.size >= this.maximumEntries) {
      throw new ConsoleAuthStoreCapacityError();
    }
    this.attempts.set(identityDigest, Object.freeze({
      count: 1,
      expiresAtEpochMillis: nowEpochMillis + this.windowMillis,
    }));
  }

  clear(identityDigest: string): void {
    requireDigest(identityDigest);
    this.attempts.delete(identityDigest);
  }

  private purgeExpired(nowEpochMillis: number): void {
    for (const [digest, attempt] of this.attempts) {
      if (attempt.expiresAtEpochMillis <= nowEpochMillis) {
        this.attempts.delete(digest);
      }
    }
  }
}

function requireBoundedInteger(value: number, minimum: number, maximum: number): number {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error("Console login limiter configuration is invalid.");
  }
  return value;
}

function requireDigest(value: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new Error("Console login identity digest is invalid.");
  }
}

function requireTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error("Console login attempt time is invalid.");
  }
}
