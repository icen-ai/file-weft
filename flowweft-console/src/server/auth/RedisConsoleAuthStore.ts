import "server-only";

import { createClient } from "redis";
import { ConsoleAuthRecordCodec } from "@/server/auth/ConsoleAuthRecordCodec";
import {
  assertPendingOidcAuthorization,
  assertStoredConsoleSession,
  ConsoleAuthStoreCapacityError,
  type ConsoleAuthStore,
  type PendingOidcAuthorization,
  type StoredConsoleSession,
} from "@/server/auth/ConsoleAuthStore";
import type { ConsoleRedisSessionStoreDefinition } from "@/server/config/schema";

const CREATE_RECORD_SCRIPT = `
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
if tonumber(redis.call('ZCARD', KEYS[2])) >= tonumber(ARGV[5]) then return -1 end
local ttl = tonumber(ARGV[2]) - tonumber(ARGV[1])
if ttl <= 0 then return -2 end
local stored = redis.call('SET', KEYS[1], ARGV[4], 'NX', 'PX', ttl)
if not stored then return 0 end
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
return 1
`;

const CONSUME_RECORD_SCRIPT = `
local value = redis.call('GET', KEYS[1])
redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return value
`;

export class ConsoleAuthStoreUnavailableError extends Error {
  constructor() {
    super("The shared Console authentication store is unavailable.");
    this.name = "ConsoleAuthStoreUnavailableError";
  }
}

export class RedisConsoleAuthStore implements ConsoleAuthStore {
  private readonly client;
  private readonly codec: ConsoleAuthRecordCodec;
  private connection: Promise<void> | null = null;

  constructor(
    private readonly definition: ConsoleRedisSessionStoreDefinition,
    private readonly maximumAuthorizations: number,
    private readonly maximumSessions: number,
  ) {
    requireCapacity(maximumAuthorizations);
    requireCapacity(maximumSessions);
    this.codec = new ConsoleAuthRecordCodec(definition.encryptionKeys);
    this.client = createClient({
      url: definition.url,
      disableOfflineQueue: true,
      socket: {
        connectTimeout: 5_000,
        reconnectStrategy: false,
      },
    });
    // node-redis requires an error listener. Errors remain deliberately
    // unlogged here because Redis URLs may contain credentials; callers expose
    // only the fixed ConsoleAuthStoreUnavailableError classification.
    this.client.on("error", () => {});
  }

  async checkAvailability(): Promise<void> {
    await this.ensureConnected();
    try {
      if (await this.client.ping() !== "PONG") {
        throw new ConsoleAuthStoreUnavailableError();
      }
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
  }

  async createAuthorization(authorization: PendingOidcAuthorization): Promise<void> {
    assertPendingOidcAuthorization(authorization);
    const wire = this.codec.encodeAuthorization(authorization);
    await this.createRecord(
      this.authorizationKey(authorization.stateDigest),
      this.authorizationIndexKey(),
      authorization.stateDigest,
      wire,
      authorization.createdAtEpochMillis,
      authorization.expiresAtEpochMillis,
      this.maximumAuthorizations,
    );
  }

  async consumeAuthorization(
    stateDigest: string,
    nowEpochMillis: number,
  ): Promise<PendingOidcAuthorization | null> {
    requireDigest(stateDigest);
    requireTime(nowEpochMillis);
    const wire = await this.consumeRecord(
      this.authorizationKey(stateDigest),
      this.authorizationIndexKey(),
      stateDigest,
    );
    if (wire === null) {
      return null;
    }
    try {
      const authorization = this.codec.decodeAuthorization(wire, stateDigest);
      if (nowEpochMillis < authorization.createdAtEpochMillis || nowEpochMillis >= authorization.expiresAtEpochMillis) {
        return null;
      }
      return authorization;
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
  }

  async createSession(session: StoredConsoleSession): Promise<void> {
    assertStoredConsoleSession(session);
    const wire = this.codec.encodeSession(session);
    await this.createRecord(
      this.sessionKey(session.sessionIdDigest),
      this.sessionIndexKey(),
      session.sessionIdDigest,
      wire,
      session.createdAtEpochMillis,
      session.expiresAtEpochMillis,
      this.maximumSessions,
    );
  }

  async readSession(sessionIdDigest: string, nowEpochMillis: number): Promise<StoredConsoleSession | null> {
    requireDigest(sessionIdDigest);
    requireTime(nowEpochMillis);
    await this.ensureConnected();
    let wire: string | null;
    try {
      wire = await this.client.get(this.sessionKey(sessionIdDigest));
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
    if (wire === null) {
      await this.removeIndexMember(this.sessionIndexKey(), sessionIdDigest);
      return null;
    }
    let session: StoredConsoleSession;
    try {
      session = this.codec.decodeSession(wire, sessionIdDigest);
    } catch {
      await this.revokeSession(sessionIdDigest).catch(() => {});
      throw new ConsoleAuthStoreUnavailableError();
    }
    if (nowEpochMillis < session.createdAtEpochMillis || nowEpochMillis >= session.expiresAtEpochMillis) {
      await this.revokeSession(sessionIdDigest);
      return null;
    }
    return session;
  }

  async revokeSession(sessionIdDigest: string): Promise<void> {
    requireDigest(sessionIdDigest);
    await this.ensureConnected();
    try {
      await this.client.multi()
        .del(this.sessionKey(sessionIdDigest))
        .zRem(this.sessionIndexKey(), sessionIdDigest)
        .exec();
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
  }

  /** Test/controlled-shutdown hook; request paths never disconnect the shared singleton. */
  async close(): Promise<void> {
    try {
      if (this.client.isOpen) {
        await this.client.quit();
      }
    } catch {
      this.client.destroy();
    }
  }

  private async createRecord(
    recordKey: string,
    indexKey: string,
    digest: string,
    wire: string,
    nowEpochMillis: number,
    expiresAtEpochMillis: number,
    maximumRecords: number,
  ): Promise<void> {
    requireTime(nowEpochMillis);
    requireTime(expiresAtEpochMillis);
    await this.ensureConnected();
    let result: unknown;
    try {
      result = await this.client.eval(CREATE_RECORD_SCRIPT, {
        keys: [recordKey, indexKey],
        arguments: [
          String(nowEpochMillis),
          String(expiresAtEpochMillis),
          digest,
          wire,
          String(maximumRecords),
        ],
      });
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
    if (result === -1) {
      throw new ConsoleAuthStoreCapacityError();
    }
    if (result === 0) {
      throw new Error("Console authentication digest already exists.");
    }
    if (result !== 1) {
      throw new ConsoleAuthStoreUnavailableError();
    }
  }

  private async consumeRecord(recordKey: string, indexKey: string, digest: string): Promise<string | null> {
    await this.ensureConnected();
    let result: unknown;
    try {
      result = await this.client.eval(CONSUME_RECORD_SCRIPT, {
        keys: [recordKey, indexKey],
        arguments: [digest],
      });
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
    if (result === null) {
      return null;
    }
    if (typeof result !== "string") {
      throw new ConsoleAuthStoreUnavailableError();
    }
    return result;
  }

  private async removeIndexMember(indexKey: string, digest: string): Promise<void> {
    await this.ensureConnected();
    try {
      await this.client.zRem(indexKey, digest);
    } catch {
      throw new ConsoleAuthStoreUnavailableError();
    }
  }

  private async ensureConnected(): Promise<void> {
    if (this.client.isReady) {
      return;
    }
    // A socket may remain open briefly after an unsuccessful handshake. Do
    // not call connect() again in that state: node-redis would surface a
    // transport-specific "socket already opened" error and request latency
    // would depend on client internals. Fail closed with the stable store
    // classification instead.
    if (this.client.isOpen && !this.connection) {
      throw new ConsoleAuthStoreUnavailableError();
    }
    if (!this.connection) {
      this.connection = this.client.connect().then(() => undefined).catch(() => {
        throw new ConsoleAuthStoreUnavailableError();
      }).finally(() => {
        this.connection = null;
      });
    }
    await this.connection;
  }

  private authorizationKey(digest: string): string {
    return `${this.definition.keyPrefix}:{authorization}:record:${digest}`;
  }

  private authorizationIndexKey(): string {
    return `${this.definition.keyPrefix}:{authorization}:index`;
  }

  private sessionKey(digest: string): string {
    return `${this.definition.keyPrefix}:{session}:record:${digest}`;
  }

  private sessionIndexKey(): string {
    return `${this.definition.keyPrefix}:{session}:index`;
  }
}

function requireCapacity(value: number): void {
  if (!Number.isSafeInteger(value) || value < 1 || value > 100_000) {
    throw new ConsoleAuthStoreUnavailableError();
  }
}

function requireDigest(value: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new ConsoleAuthStoreUnavailableError();
  }
}

function requireTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new ConsoleAuthStoreUnavailableError();
  }
}
