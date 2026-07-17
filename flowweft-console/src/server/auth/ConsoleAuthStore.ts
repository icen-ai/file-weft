import "server-only";

export interface PendingOidcAuthorization {
  readonly stateDigest: string;
  readonly consoleOriginBindingDigest: string;
  readonly sourceProfileId: string;
  readonly sourceProfileBindingDigest: string;
  readonly nonce: string;
  readonly pkceVerifier: string;
  readonly redirectUri: string;
  readonly returnPath: string;
  readonly createdAtEpochMillis: number;
  readonly expiresAtEpochMillis: number;
}

export interface StoredConsoleSession {
  readonly sessionIdDigest: string;
  readonly consoleOriginBindingDigest: string;
  readonly sourceProfileId: string;
  readonly sourceProfileBindingDigest: string;
  readonly subjectId: string;
  readonly subjectDisplayName: string;
  readonly tenantAlias: string;
  readonly accessToken: string;
  readonly tokenType: "Bearer";
  readonly createdAtEpochMillis: number;
  readonly expiresAtEpochMillis: number;
}

export interface ConsoleAuthStore {
  checkAvailability(): Promise<void>;
  createAuthorization(authorization: PendingOidcAuthorization): Promise<void>;
  consumeAuthorization(stateDigest: string, nowEpochMillis: number): Promise<PendingOidcAuthorization | null>;
  createSession(session: StoredConsoleSession): Promise<void>;
  readSession(sessionIdDigest: string, nowEpochMillis: number): Promise<StoredConsoleSession | null>;
  revokeSession(sessionIdDigest: string): Promise<void>;
}

export class ConsoleAuthStoreCapacityError extends Error {
  constructor() {
    super("The Console authentication store reached its configured capacity.");
    this.name = "ConsoleAuthStoreCapacityError";
  }
}

export interface InMemoryConsoleAuthStoreOptions {
  readonly maximumAuthorizations?: number;
  readonly maximumSessions?: number;
}

/**
 * Bounded single-process store. It is intentionally explicit: clustered deployments must replace
 * it with a shared, TTL-enforcing store before enabling more than one Console replica.
 */
export class InMemoryConsoleAuthStore implements ConsoleAuthStore {
  private readonly authorizations = new Map<string, PendingOidcAuthorization>();
  private readonly sessions = new Map<string, StoredConsoleSession>();
  private readonly maximumAuthorizations: number;
  private readonly maximumSessions: number;

  constructor(options: InMemoryConsoleAuthStoreOptions = {}) {
    this.maximumAuthorizations = requireCapacity(options.maximumAuthorizations ?? 1_000);
    this.maximumSessions = requireCapacity(options.maximumSessions ?? 10_000);
  }

  async checkAvailability(): Promise<void> {
    // Process-local state is available for the lifetime of this instance.
    return;
  }

  async createAuthorization(authorization: PendingOidcAuthorization): Promise<void> {
    assertPendingOidcAuthorization(authorization);
    this.purgeExpiredAuthorizations(authorization.createdAtEpochMillis);
    if (this.authorizations.has(authorization.stateDigest)) {
      throw new Error("OIDC authorization state digest already exists.");
    }
    if (this.authorizations.size >= this.maximumAuthorizations) {
      throw new ConsoleAuthStoreCapacityError();
    }
    this.authorizations.set(authorization.stateDigest, Object.freeze({ ...authorization }));
  }

  async consumeAuthorization(stateDigest: string, nowEpochMillis: number): Promise<PendingOidcAuthorization | null> {
    requireDigest(stateDigest);
    requireTime(nowEpochMillis);
    const authorization = this.authorizations.get(stateDigest);
    this.authorizations.delete(stateDigest);
    if (!authorization || nowEpochMillis < authorization.createdAtEpochMillis ||
      nowEpochMillis >= authorization.expiresAtEpochMillis) {
      return null;
    }
    return authorization;
  }

  async createSession(session: StoredConsoleSession): Promise<void> {
    assertStoredConsoleSession(session);
    this.purgeExpiredSessions(session.createdAtEpochMillis);
    if (this.sessions.has(session.sessionIdDigest)) {
      throw new Error("Console session digest already exists.");
    }
    if (this.sessions.size >= this.maximumSessions) {
      throw new ConsoleAuthStoreCapacityError();
    }
    this.sessions.set(session.sessionIdDigest, Object.freeze({ ...session }));
  }

  async readSession(sessionIdDigest: string, nowEpochMillis: number): Promise<StoredConsoleSession | null> {
    requireDigest(sessionIdDigest);
    requireTime(nowEpochMillis);
    const session = this.sessions.get(sessionIdDigest);
    if (!session || nowEpochMillis < session.createdAtEpochMillis || nowEpochMillis >= session.expiresAtEpochMillis) {
      if (session) {
        this.sessions.delete(sessionIdDigest);
      }
      return null;
    }
    return session;
  }

  async revokeSession(sessionIdDigest: string): Promise<void> {
    requireDigest(sessionIdDigest);
    this.sessions.delete(sessionIdDigest);
  }

  private purgeExpiredAuthorizations(nowEpochMillis: number): void {
    for (const [digest, authorization] of this.authorizations) {
      if (authorization.expiresAtEpochMillis <= nowEpochMillis) {
        this.authorizations.delete(digest);
      }
    }
  }

  private purgeExpiredSessions(nowEpochMillis: number): void {
    for (const [digest, session] of this.sessions) {
      if (session.expiresAtEpochMillis <= nowEpochMillis) {
        this.sessions.delete(digest);
      }
    }
  }
}

export function assertPendingOidcAuthorization(value: PendingOidcAuthorization): void {
  requireDigest(value.stateDigest);
  requireDigest(value.consoleOriginBindingDigest);
  requireOpaqueText(value.sourceProfileId, 80);
  requireDigest(value.sourceProfileBindingDigest);
  requireOpaqueText(value.nonce, 256);
  if (!/^[A-Za-z0-9._~-]{43,128}$/u.test(value.pkceVerifier)) {
    throw new Error("OIDC PKCE verifier is invalid.");
  }
  requireAbsoluteUrl(value.redirectUri);
  if (!/^\/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+\/?)*$/u.test(value.returnPath) || value.returnPath.startsWith("//")) {
    throw new Error("OIDC return path is invalid.");
  }
  requireLifetime(value.createdAtEpochMillis, value.expiresAtEpochMillis, 10 * 60_000);
}

export function assertStoredConsoleSession(value: StoredConsoleSession): void {
  requireDigest(value.sessionIdDigest);
  requireDigest(value.consoleOriginBindingDigest);
  requireOpaqueText(value.sourceProfileId, 80);
  requireDigest(value.sourceProfileBindingDigest);
  requireOpaqueText(value.subjectId, 512);
  requireDisplayText(value.subjectDisplayName, 256);
  requireDisplayText(value.tenantAlias, 256);
  requireOpaqueText(value.accessToken, 16_384);
  if (value.tokenType !== "Bearer") {
    throw new Error("Console session token type is invalid.");
  }
  requireLifetime(value.createdAtEpochMillis, value.expiresAtEpochMillis, 24 * 60 * 60_000);
}

function requireCapacity(value: number): number {
  if (!Number.isSafeInteger(value) || value < 1 || value > 100_000) {
    throw new Error("Console authentication store capacity is invalid.");
  }
  return value;
}

function requireLifetime(createdAt: number, expiresAt: number, maximumLifetimeMillis: number): void {
  requireTime(createdAt);
  requireTime(expiresAt);
  if (expiresAt <= createdAt || expiresAt - createdAt > maximumLifetimeMillis) {
    throw new Error("Console authentication record lifetime is invalid.");
  }
}

function requireTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error("Console authentication time is invalid.");
  }
}

function requireDigest(value: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new Error("Console authentication digest is invalid.");
  }
}

function requireOpaqueText(value: string, maximumLength: number): void {
  if (value.length < 1 || value.length > maximumLength || /[\u0000-\u001f\u007f\s]/u.test(value)) {
    throw new Error("Console authentication token is invalid.");
  }
}

function requireDisplayText(value: string, maximumLength: number): void {
  if (value.trim() !== value || value.length < 1 || value.length > maximumLength || /[\u0000-\u001f\u007f]/u.test(value)) {
    throw new Error("Console session display value is invalid.");
  }
}

function requireAbsoluteUrl(value: string): void {
  if (value.length > 2_048) {
    throw new Error("OIDC redirect URI is invalid.");
  }
  const endpoint = new URL(value);
  if (!/^https?:$/u.test(endpoint.protocol) || endpoint.username !== "" || endpoint.password !== "" ||
    endpoint.hash !== "") {
    throw new Error("OIDC redirect URI is invalid.");
  }
}
