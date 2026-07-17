import "server-only";
import { createHash, timingSafeEqual } from "node:crypto";
import { decodeProtectedHeader, importJWK, jwtVerify, type JWK, type JWTPayload } from "jose";
import { z } from "zod";
import type { ConsoleSessionProjection } from "@/contracts/bff";
import type { ConsoleServerConfig, ConsoleSourceProfileDefinition } from "@/server/config/schema";
import type { ConsoleAuthStore, StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import type { SourceProfileRegistry } from "@/server/sources/SourceProfileRegistry";
import {
  createAuthorizationState,
  createOidcNonce,
  createPkceChallenge,
  createPkceVerifier,
  createSessionId,
  sha256Base64Url,
} from "@/server/auth/OidcCrypto";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";
import { consoleOriginBindingDigest } from "@/server/security/ConsoleOriginBinding";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const tokenResponseSchema = z.object({
  access_token: z.string().min(1).max(16_384).regex(/^[\x21-\x7e]+$/u),
  token_type: z.string().min(1).max(32),
  expires_in: z.number().int().min(1).max(86_400),
  id_token: z.string().min(1).max(65_536).regex(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/u),
}).passthrough();

const jwksSchema = z.object({
  keys: z.array(z.record(z.string(), z.unknown())).min(1).max(32),
}).passthrough();

export class OidcLoginError extends Error {
  readonly code: "NOT_CONFIGURED" | "INVALID_CALLBACK" | "UPSTREAM_FAILURE" | "INVALID_IDENTITY";

  constructor(code: OidcLoginError["code"]) {
    super(`OIDC login failed: ${code}.`);
    this.name = "OidcLoginError";
    this.code = code;
  }
}

export interface OidcAuthorizationRedirect {
  readonly location: string;
}

export interface OidcLoginCompletion {
  readonly sessionId: string;
  readonly session: ConsoleSessionProjection;
  readonly returnPath: string;
}

export class OidcLoginService {
  constructor(
    private readonly config: ConsoleServerConfig,
    private readonly store: ConsoleAuthStore,
    private readonly sources: SourceProfileRegistry,
  ) {}

  async begin(
    profile: ConsoleSourceProfileDefinition,
    returnPath: string,
    nowEpochMillis: number,
  ): Promise<OidcAuthorizationRedirect> {
    const oidc = profile.oidc;
    if (!oidc || !this.config.publicOrigin) {
      throw new OidcLoginError("NOT_CONFIGURED");
    }
    requireSafeReturnPath(returnPath);
    requireTime(nowEpochMillis);
    const state = createAuthorizationState();
    const nonce = createOidcNonce();
    const pkceVerifier = createPkceVerifier();
    const redirectUri = `${this.config.publicOrigin}/api/auth/oidc/callback`;
    await this.store.createAuthorization(Object.freeze({
      stateDigest: sha256Base64Url(state),
      consoleOriginBindingDigest: consoleOriginBindingDigest(this.config),
      sourceProfileId: profile.id,
      sourceProfileBindingDigest: sourceProfileBindingDigest(profile),
      nonce,
      pkceVerifier,
      redirectUri,
      returnPath,
      createdAtEpochMillis: nowEpochMillis,
      expiresAtEpochMillis: nowEpochMillis + this.config.authorizationTtlSeconds * 1_000,
    }));

    const endpoint = new URL(oidc.authorizationEndpoint);
    endpoint.searchParams.set("client_id", oidc.clientId);
    endpoint.searchParams.set("redirect_uri", redirectUri);
    endpoint.searchParams.set("response_type", "code");
    endpoint.searchParams.set("response_mode", "query");
    endpoint.searchParams.set("scope", oidc.scopes.join(" "));
    endpoint.searchParams.set("state", state);
    endpoint.searchParams.set("nonce", nonce);
    endpoint.searchParams.set("code_challenge", createPkceChallenge(pkceVerifier));
    endpoint.searchParams.set("code_challenge_method", "S256");
    return Object.freeze({ location: endpoint.toString() });
  }

  async abandon(state: string, nowEpochMillis: number): Promise<string | null> {
    requireAuthorizationState(state);
    requireTime(nowEpochMillis);
    return (await this.store.consumeAuthorization(sha256Base64Url(state), nowEpochMillis))?.returnPath ?? null;
  }

  async complete(
    state: string,
    code: string,
    nowEpochMillis: number,
  ): Promise<OidcLoginCompletion> {
    requireAuthorizationState(state);
    requireCallbackToken(code, 4_096);
    requireTime(nowEpochMillis);
    const authorization = await this.store.consumeAuthorization(sha256Base64Url(state), nowEpochMillis);
    if (!authorization || !this.config.publicOrigin ||
      authorization.redirectUri !== `${this.config.publicOrigin}/api/auth/oidc/callback`) {
      throw new OidcLoginError("INVALID_CALLBACK");
    }
    let profile: ConsoleSourceProfileDefinition;
    try {
      profile = this.sources.requireDefinition(authorization.sourceProfileId);
    } catch {
      throw new OidcLoginError("NOT_CONFIGURED");
    }
    const oidc = profile.oidc;
    if (!oidc ||
      authorization.consoleOriginBindingDigest !== consoleOriginBindingDigest(this.config) ||
      authorization.sourceProfileBindingDigest !== sourceProfileBindingDigest(profile)) {
      throw new OidcLoginError("NOT_CONFIGURED");
    }

    const form = new URLSearchParams({
      grant_type: "authorization_code",
      client_id: oidc.clientId,
      code,
      code_verifier: authorization.pkceVerifier,
      redirect_uri: authorization.redirectUri,
    }).toString();
    let rawTokens: unknown;
    try {
      rawTokens = await requestPinnedJson({
        url: oidc.tokenEndpoint,
        method: "POST",
        body: form,
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        timeoutMillis: 10_000,
        allowPrivateNetwork: oidc.allowPrivateNetwork,
      });
    } catch {
      throw new OidcLoginError("UPSTREAM_FAILURE");
    }
    const tokens = tokenResponseSchema.safeParse(rawTokens);
    if (!tokens.success || tokens.data.token_type.toLowerCase() !== "bearer") {
      throw new OidcLoginError("UPSTREAM_FAILURE");
    }
    const identity = await this.verifyIdentityToken(
      profile,
      tokens.data.id_token,
      authorization.nonce,
      tokens.data.access_token,
      nowEpochMillis,
    );
    const sessionId = createSessionId();
    const maximumExpiry = nowEpochMillis + this.config.sessionTtlSeconds * 1_000;
    const accessTokenExpiry = nowEpochMillis + tokens.data.expires_in * 1_000;
    const identityExpiry = requireIdentityExpiry(identity.payload, nowEpochMillis);
    const expiresAtEpochMillis = Math.min(maximumExpiry, accessTokenExpiry, identityExpiry);
    if (expiresAtEpochMillis <= nowEpochMillis) {
      throw new OidcLoginError("INVALID_IDENTITY");
    }
    const session: StoredConsoleSession = Object.freeze({
      sessionIdDigest: sha256Base64Url(sessionId),
      consoleOriginBindingDigest: consoleOriginBindingDigest(this.config),
      sourceProfileId: profile.id,
      sourceProfileBindingDigest: sourceProfileBindingDigest(profile),
      subjectId: identity.subjectId,
      subjectDisplayName: identity.subjectDisplayName,
      tenantAlias: identity.tenantAlias,
      accessToken: tokens.data.access_token,
      tokenType: "Bearer",
      createdAtEpochMillis: nowEpochMillis,
      expiresAtEpochMillis,
    });
    await this.store.createSession(session);
    return Object.freeze({
      sessionId,
      session: projectSession(session),
      returnPath: authorization.returnPath,
    });
  }

  private async verifyIdentityToken(
    profile: ConsoleSourceProfileDefinition,
    token: string,
    expectedNonce: string,
    accessToken: string,
    nowEpochMillis: number,
  ): Promise<{
    readonly payload: JWTPayload;
    readonly subjectId: string;
    readonly subjectDisplayName: string;
    readonly tenantAlias: string;
  }> {
    const oidc = profile.oidc;
    if (!oidc) {
      throw new OidcLoginError("NOT_CONFIGURED");
    }
    let header: ReturnType<typeof decodeProtectedHeader>;
    try {
      header = decodeProtectedHeader(token);
    } catch {
      throw new OidcLoginError("INVALID_IDENTITY");
    }
    if (typeof header.alg !== "string" || !oidc.allowedAlgorithms.includes(header.alg as never) ||
      typeof header.kid !== "string" || header.kid.length < 1 || header.kid.length > 256) {
      throw new OidcLoginError("INVALID_IDENTITY");
    }

    let rawJwks: unknown;
    try {
      rawJwks = await requestPinnedJson({
        url: oidc.jwksUri,
        method: "GET",
        timeoutMillis: 10_000,
        allowPrivateNetwork: oidc.allowPrivateNetwork,
      });
    } catch {
      throw new OidcLoginError("UPSTREAM_FAILURE");
    }
    const jwks = jwksSchema.safeParse(rawJwks);
    if (!jwks.success) {
      throw new OidcLoginError("INVALID_IDENTITY");
    }
    const candidates = jwks.data.keys.filter((key) =>
      key.kid === header.kid &&
      (key.alg === undefined || key.alg === header.alg) &&
      (key.use === undefined || key.use === "sig"),
    );
    if (candidates.length !== 1) {
      throw new OidcLoginError("INVALID_IDENTITY");
    }

    try {
      const key = await importJWK(candidates[0] as JWK, header.alg);
      const verified = await jwtVerify(token, key, {
        algorithms: [...oidc.allowedAlgorithms],
        audience: oidc.clientId,
        issuer: oidc.issuer,
        clockTolerance: 30,
        currentDate: new Date(nowEpochMillis),
      });
      if (verified.protectedHeader.kid !== header.kid || verified.payload.nonce !== expectedNonce) {
        throw new OidcLoginError("INVALID_IDENTITY");
      }
      const audiences = Array.isArray(verified.payload.aud) ? verified.payload.aud : [verified.payload.aud];
      if ((audiences.length > 1 || verified.payload.azp !== undefined) &&
        verified.payload.azp !== oidc.clientId) {
        throw new OidcLoginError("INVALID_IDENTITY");
      }
      if (verified.payload.at_hash !== undefined) {
        if (typeof verified.payload.at_hash !== "string" ||
          !safeTokenHashEquals(verified.payload.at_hash, accessToken)) {
          throw new OidcLoginError("INVALID_IDENTITY");
        }
      }
      const subjectId = requireIdentityText(verified.payload.sub, 512, false);
      const subjectDisplayName = requireIdentityText(verified.payload[oidc.displayNameClaim], 256, true);
      const tenantAlias = requireIdentityText(verified.payload[oidc.tenantAliasClaim], 256, true);
      return Object.freeze({ payload: verified.payload, subjectId, subjectDisplayName, tenantAlias });
    } catch (error) {
      if (error instanceof OidcLoginError) {
        throw error;
      }
      throw new OidcLoginError("INVALID_IDENTITY");
    }
  }
}

function safeTokenHashEquals(expected: string, accessToken: string): boolean {
  const digest = createHash("sha256").update(accessToken, "ascii").digest();
  const actual = Buffer.from(digest.subarray(0, digest.length / 2)).toString("base64url");
  const expectedBytes = Buffer.from(expected, "utf8");
  const actualBytes = Buffer.from(actual, "utf8");
  return expectedBytes.length === actualBytes.length && timingSafeEqual(expectedBytes, actualBytes);
}

export function projectSession(session: StoredConsoleSession): ConsoleSessionProjection {
  return Object.freeze({
    subjectDisplayName: session.subjectDisplayName,
    tenantAlias: session.tenantAlias,
    sourceProfileId: session.sourceProfileId,
    expiresAt: new Date(session.expiresAtEpochMillis).toISOString(),
    capabilities: Object.freeze({}),
  });
}

function requireIdentityExpiry(payload: JWTPayload, nowEpochMillis: number): number {
  if (!Number.isSafeInteger(payload.exp) || !Number.isSafeInteger(payload.iat)) {
    throw new OidcLoginError("INVALID_IDENTITY");
  }
  const issuedAt = Number(payload.iat) * 1_000;
  const expiresAt = Number(payload.exp) * 1_000;
  if (issuedAt > nowEpochMillis + 30_000 || issuedAt < nowEpochMillis - 10 * 60_000 || expiresAt <= nowEpochMillis) {
    throw new OidcLoginError("INVALID_IDENTITY");
  }
  return expiresAt;
}

function requireIdentityText(value: unknown, maximumLength: number, displayText: boolean): string {
  if (typeof value !== "string" || value.length < 1 || value.length > maximumLength ||
    /[\u0000-\u001f\u007f]/u.test(value) || displayText && value.trim() !== value ||
    !displayText && /\s/u.test(value)) {
    throw new OidcLoginError("INVALID_IDENTITY");
  }
  return value;
}

function requireCallbackToken(value: string, maximumLength: number): void {
  if (value.length < 1 || value.length > maximumLength || !/^[\x21-\x7e]+$/u.test(value)) {
    throw new OidcLoginError("INVALID_CALLBACK");
  }
}

function requireAuthorizationState(value: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new OidcLoginError("INVALID_CALLBACK");
  }
}

function requireSafeReturnPath(path: string): void {
  if (!/^\/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+\/?)*$/u.test(path) || path.startsWith("//")) {
    throw new OidcLoginError("INVALID_CALLBACK");
  }
}

function requireTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new OidcLoginError("INVALID_CALLBACK");
  }
}
