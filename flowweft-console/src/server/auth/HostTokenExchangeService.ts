import "server-only";

import { z } from "zod";
import type { ConsoleSessionProjection } from "@/contracts/bff";
import type { ConsoleAuthStore, StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import type { ConsoleLoginAttemptLimiter } from "@/server/auth/LoginAttemptLimiter";
import { createSessionId, sha256Base64Url } from "@/server/auth/OidcCrypto";
import { projectSession } from "@/server/auth/OidcLoginService";
import type { ConsoleServerConfig, ConsoleSourceProfileDefinition } from "@/server/config/schema";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";

const exchangeResponseSchema = z.object({
  access_token: z.string().min(1).max(16_384).regex(/^[\x21-\x7e]+$/u),
  token_type: z.string().min(1).max(32),
  expires_in: z.number().int().min(1).max(86_400),
  subject_id: z.string().min(1).max(512).regex(/^[^\s\u0000-\u001f\u007f]+$/u),
  subject_display_name: z.string().trim().min(1).max(256).regex(/^[^\u0000-\u001f\u007f]+$/u),
  tenant_alias: z.string().trim().min(1).max(256).regex(/^[^\u0000-\u001f\u007f]+$/u),
}).strict();

export class HostTokenExchangeError extends Error {
  readonly code: "NOT_CONFIGURED" | "INVALID_REQUEST" | "REJECTED" | "INVALID_IDENTITY";

  constructor(code: HostTokenExchangeError["code"]) {
    super(`Host token exchange failed: ${code}.`);
    this.name = "HostTokenExchangeError";
    this.code = code;
  }
}

export interface HostTokenExchangeInput {
  readonly tenantAlias: string;
  readonly username: string;
  readonly password: string;
}

export interface HostTokenExchangeCompletion {
  readonly sessionId: string;
  readonly session: ConsoleSessionProjection;
  readonly returnPath: string;
}

export class HostTokenExchangeService {
  constructor(
    private readonly config: ConsoleServerConfig,
    private readonly store: ConsoleAuthStore,
    private readonly limiter: ConsoleLoginAttemptLimiter,
  ) {}

  async exchange(
    profile: ConsoleSourceProfileDefinition,
    input: HostTokenExchangeInput,
    returnPath: string,
    nowEpochMillis: number,
  ): Promise<HostTokenExchangeCompletion> {
    const exchange = profile.hostTokenExchange;
    if (!exchange || !this.config.publicOrigin ||
      !profile.authenticationModes.includes("HOST_TOKEN_EXCHANGE")) {
      throw new HostTokenExchangeError("NOT_CONFIGURED");
    }
    requireDisplayCredential(input.tenantAlias, 256);
    requireDisplayCredential(input.username, 256);
    requirePassword(input.password);
    requireSafeReturnPath(returnPath);
    requireTime(nowEpochMillis);

    const identityDigest = sha256Base64Url(
      `${profile.id}\u0000${input.tenantAlias}\u0000${input.username}`,
    );
    this.limiter.acquire(identityDigest, nowEpochMillis);
    const endpoint = new URL(exchange.endpointPath, profile.baseUrl);
    if (endpoint.origin !== profile.baseUrl || endpoint.pathname !== exchange.endpointPath ||
      endpoint.search !== "" || endpoint.hash !== "") {
      throw new HostTokenExchangeError("NOT_CONFIGURED");
    }

    const body = new URLSearchParams({
      grant_type: "urn:flowweft:params:oauth:grant-type:host-token-exchange",
      tenant_alias: input.tenantAlias,
      username: input.username,
      password: input.password,
    }).toString();
    let rawResponse: unknown;
    try {
      rawResponse = await requestPinnedJson({
        url: endpoint.toString(),
        method: "POST",
        body,
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        timeoutMillis: 10_000,
        allowPrivateNetwork: exchange.allowPrivateNetwork,
      });
    } catch {
      throw new HostTokenExchangeError("REJECTED");
    }
    const response = exchangeResponseSchema.safeParse(rawResponse);
    if (!response.success || response.data.token_type.toLowerCase() !== "bearer" ||
      response.data.tenant_alias !== input.tenantAlias) {
      throw new HostTokenExchangeError("INVALID_IDENTITY");
    }

    // A valid upstream identity clears the compatibility-path attempt bucket
    // even if the bounded local session store is temporarily at capacity.
    this.limiter.clear(identityDigest);
    const sessionId = createSessionId();
    const expiresAtEpochMillis = Math.min(
      nowEpochMillis + this.config.sessionTtlSeconds * 1_000,
      nowEpochMillis + response.data.expires_in * 1_000,
    );
    const session: StoredConsoleSession = Object.freeze({
      sessionIdDigest: sha256Base64Url(sessionId),
      sourceProfileId: profile.id,
      subjectId: response.data.subject_id,
      subjectDisplayName: response.data.subject_display_name,
      tenantAlias: response.data.tenant_alias,
      accessToken: response.data.access_token,
      tokenType: "Bearer",
      createdAtEpochMillis: nowEpochMillis,
      expiresAtEpochMillis,
    });
    await this.store.createSession(session);
    return Object.freeze({
      sessionId,
      session: projectSession(session),
      returnPath,
    });
  }
}

function requireDisplayCredential(value: string, maximumLength: number): void {
  if (value.trim() !== value || value.length < 1 || value.length > maximumLength ||
    /[\u0000-\u001f\u007f]/u.test(value)) {
    throw new HostTokenExchangeError("INVALID_REQUEST");
  }
}

function requirePassword(value: string): void {
  if (value.length < 1 || value.length > 4_096 || /[\u0000-\u001f\u007f]/u.test(value)) {
    throw new HostTokenExchangeError("INVALID_REQUEST");
  }
}

function requireSafeReturnPath(path: string): void {
  if (!/^\/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+\/?)*$/u.test(path) || path.startsWith("//")) {
    throw new HostTokenExchangeError("INVALID_REQUEST");
  }
}

function requireTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new HostTokenExchangeError("INVALID_REQUEST");
  }
}
