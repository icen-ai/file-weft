import "server-only";

import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import {
  assertPendingOidcAuthorization,
  assertStoredConsoleSession,
  type PendingOidcAuthorization,
  type StoredConsoleSession,
} from "@/server/auth/ConsoleAuthStore";
import type { ConsoleSessionEncryptionKeyDefinition } from "@/server/config/schema";

type RecordKind = "authorization" | "session";
const WIRE_VERSION = "v1";
const MAXIMUM_WIRE_BYTES = 96 * 1_024;
const AUTHORIZATION_KEYS = [
  "createdAtEpochMillis",
  "expiresAtEpochMillis",
  "nonce",
  "pkceVerifier",
  "redirectUri",
  "returnPath",
  "sourceProfileId",
  "stateDigest",
] as const;
const SESSION_KEYS = [
  "accessToken",
  "createdAtEpochMillis",
  "expiresAtEpochMillis",
  "sessionIdDigest",
  "sourceProfileId",
  "subjectDisplayName",
  "subjectId",
  "tenantAlias",
  "tokenType",
] as const;

export class ConsoleAuthRecordCodecError extends Error {
  constructor() {
    super("Console authentication record could not be authenticated.");
    this.name = "ConsoleAuthRecordCodecError";
  }
}

export class ConsoleAuthRecordCodec {
  private readonly activeKey: { id: string; bytes: Buffer };
  private readonly keys: ReadonlyMap<string, Buffer>;

  constructor(definitions: readonly ConsoleSessionEncryptionKeyDefinition[]) {
    if (definitions.length < 1 || definitions.length > 4) {
      throw new ConsoleAuthRecordCodecError();
    }
    const keys = new Map<string, Buffer>();
    for (const definition of definitions) {
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/u.test(definition.id) ||
        keys.has(definition.id)) {
        throw new ConsoleAuthRecordCodecError();
      }
      const bytes = decodeCanonicalBase64Url(definition.key, 32);
      keys.set(definition.id, bytes);
    }
    const first = definitions[0];
    if (!first) {
      throw new ConsoleAuthRecordCodecError();
    }
    this.activeKey = Object.freeze({ id: first.id, bytes: keys.get(first.id)! });
    this.keys = keys;
  }

  encodeAuthorization(value: PendingOidcAuthorization): string {
    assertPendingOidcAuthorization(value);
    return this.encrypt("authorization", value.stateDigest, JSON.stringify(value));
  }

  decodeAuthorization(wire: string, expectedDigest: string): PendingOidcAuthorization {
    const value = this.decrypt("authorization", expectedDigest, wire);
    requireExactKeys(value, AUTHORIZATION_KEYS);
    const authorization = value as unknown as PendingOidcAuthorization;
    assertPendingOidcAuthorization(authorization);
    if (authorization.stateDigest !== expectedDigest) {
      throw new ConsoleAuthRecordCodecError();
    }
    return Object.freeze({ ...authorization });
  }

  encodeSession(value: StoredConsoleSession): string {
    assertStoredConsoleSession(value);
    return this.encrypt("session", value.sessionIdDigest, JSON.stringify(value));
  }

  decodeSession(wire: string, expectedDigest: string): StoredConsoleSession {
    const value = this.decrypt("session", expectedDigest, wire);
    requireExactKeys(value, SESSION_KEYS);
    const session = value as unknown as StoredConsoleSession;
    assertStoredConsoleSession(session);
    if (session.sessionIdDigest !== expectedDigest) {
      throw new ConsoleAuthRecordCodecError();
    }
    return Object.freeze({ ...session });
  }

  private encrypt(kind: RecordKind, digest: string, plaintext: string): string {
    requireDigest(digest);
    const nonce = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.activeKey.bytes, nonce);
    cipher.setAAD(this.aad(kind, digest));
    const ciphertext = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
    const tag = cipher.getAuthTag();
    const wire = [
      WIRE_VERSION,
      this.activeKey.id,
      nonce.toString("base64url"),
      tag.toString("base64url"),
      ciphertext.toString("base64url"),
    ].join(".");
    if (Buffer.byteLength(wire, "utf8") > MAXIMUM_WIRE_BYTES) {
      throw new ConsoleAuthRecordCodecError();
    }
    return wire;
  }

  private decrypt(kind: RecordKind, digest: string, wire: string): Record<string, unknown> {
    requireDigest(digest);
    if (typeof wire !== "string" || wire.length < 1 || Buffer.byteLength(wire, "utf8") > MAXIMUM_WIRE_BYTES) {
      throw new ConsoleAuthRecordCodecError();
    }
    const parts = wire.split(".");
    if (parts.length !== 5 || parts[0] !== WIRE_VERSION ||
      !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/u.test(parts[1] ?? "")) {
      throw new ConsoleAuthRecordCodecError();
    }
    const key = this.keys.get(parts[1]!);
    if (!key) {
      throw new ConsoleAuthRecordCodecError();
    }
    let plaintext: Buffer | null = null;
    try {
      const nonce = decodeCanonicalBase64Url(parts[2]!, 12);
      const tag = decodeCanonicalBase64Url(parts[3]!, 16);
      const ciphertext = decodeCanonicalBase64Url(parts[4]!, null);
      const decipher = createDecipheriv("aes-256-gcm", key, nonce);
      decipher.setAAD(this.aad(kind, digest));
      decipher.setAuthTag(tag);
      plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
      const text = new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
      const parsed = JSON.parse(text) as unknown;
      if (!isPlainObject(parsed)) {
        throw new ConsoleAuthRecordCodecError();
      }
      return parsed;
    } catch (error) {
      if (error instanceof ConsoleAuthRecordCodecError) {
        throw error;
      }
      throw new ConsoleAuthRecordCodecError();
    } finally {
      plaintext?.fill(0);
    }
  }

  private aad(kind: RecordKind, digest: string): Buffer {
    return Buffer.from(`flowweft-console-auth:${WIRE_VERSION}:${kind}:${digest}`, "utf8");
  }
}

function decodeCanonicalBase64Url(value: string, expectedLength: number | null): Buffer {
  if (!/^[A-Za-z0-9_-]+$/u.test(value)) {
    throw new ConsoleAuthRecordCodecError();
  }
  const decoded = Buffer.from(value, "base64url");
  if ((expectedLength !== null && decoded.length !== expectedLength) ||
    decoded.length < 1 || decoded.toString("base64url") !== value) {
    decoded.fill(0);
    throw new ConsoleAuthRecordCodecError();
  }
  return decoded;
}

function requireExactKeys(value: Record<string, unknown>, expected: readonly string[]): void {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  if (actual.length !== sortedExpected.length || actual.some((key, index) => key !== sortedExpected[index])) {
    throw new ConsoleAuthRecordCodecError();
  }
}

function requireDigest(value: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new ConsoleAuthRecordCodecError();
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
