// @vitest-environment node

import { createHash } from "node:crypto";
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/server/security/PinnedJsonHttpClient", () => ({
  requestPinnedJson: vi.fn(),
}));

import { InMemoryConsoleAuthStore } from "@/server/auth/ConsoleAuthStore";
import { createPkceChallenge, sha256Base64Url } from "@/server/auth/OidcCrypto";
import { OidcLoginService } from "@/server/auth/OidcLoginService";
import { parseConsoleServerConfig } from "@/server/config/schema";
import { createSourceProfileRegistry } from "@/server/sources/SourceProfileRegistry";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";

const requestPinnedJsonMock = vi.mocked(requestPinnedJson);

describe("OIDC authorization start", () => {
  beforeEach(() => requestPinnedJsonMock.mockReset());
  it("stores verifier and nonce server-side while returning only an authorization redirect", async () => {
    const config = oidcConfig();
    const store = new InMemoryConsoleAuthStore();
    const sources = createSourceProfileRegistry(config);
    const profile = sources.requireDefinition("primary");
    const service = new OidcLoginService(config, store, sources);

    const redirect = await service.begin(profile, "/zh", 1_000);
    const endpoint = new URL(redirect.location);
    const state = endpoint.searchParams.get("state");
    expect(endpoint.origin + endpoint.pathname).toBe("https://id.example/authorize");
    expect(endpoint.searchParams.get("response_type")).toBe("code");
    expect(endpoint.searchParams.get("code_challenge_method")).toBe("S256");
    expect(endpoint.searchParams.get("client_id")).toBe("flowweft-console");
    expect(endpoint.searchParams.get("redirect_uri")).toBe("https://console.example/api/auth/oidc/callback");
    expect(state).toMatch(/^[A-Za-z0-9_-]{43}$/u);

    const pending = await store.consumeAuthorization(sha256Base64Url(String(state)), 1_001);
    expect(pending).not.toBeNull();
    expect(createPkceChallenge(String(pending?.pkceVerifier))).toBe(endpoint.searchParams.get("code_challenge"));
    expect(pending?.nonce).toBe(endpoint.searchParams.get("nonce"));
    expect(redirect.location).not.toContain(String(pending?.pkceVerifier));
  });

  it("rejects open return redirects", async () => {
    const config = oidcConfig();
    const store = new InMemoryConsoleAuthStore();
    const sources = createSourceProfileRegistry(config);
    const service = new OidcLoginService(config, store, sources);
    await expect(service.begin(sources.requireDefinition("primary"), "//attacker.example", 1_000)).rejects.toThrow();
  });

  it("rejects a pending authorization after its administrator profile policy changes", async () => {
    const store = new InMemoryConsoleAuthStore();
    const firstConfig = oidcConfig();
    const firstSources = createSourceProfileRegistry(firstConfig);
    const redirect = await new OidcLoginService(firstConfig, store, firstSources)
      .begin(firstSources.requireDefinition("primary"), "/zh", 1_000);
    const state = new URL(redirect.location).searchParams.get("state")!;

    const changedConfig = oidcConfig("flowweft-console-v2");
    const changedSources = createSourceProfileRegistry(changedConfig);
    await expect(new OidcLoginService(changedConfig, store, changedSources)
      .complete(state, "authorization-code", 1_001))
      .rejects.toMatchObject({ code: "NOT_CONFIGURED" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();
  });

  it("rejects a pending authorization after its same-origin session boundary changes", async () => {
    const store = new InMemoryConsoleAuthStore();
    const firstConfig = oidcConfig();
    const firstSources = createSourceProfileRegistry(firstConfig);
    const redirect = await new OidcLoginService(firstConfig, store, firstSources)
      .begin(firstSources.requireDefinition("primary"), "/zh", 1_000);
    const state = new URL(redirect.location).searchParams.get("state")!;

    const changedConfig = oidcConfig("flowweft-console", "https://console.example", "900");
    const changedSources = createSourceProfileRegistry(changedConfig);
    await expect(new OidcLoginService(changedConfig, store, changedSources)
      .complete(state, "authorization-code", 1_001))
      .rejects.toMatchObject({ code: "NOT_CONFIGURED" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();
  });

  it("verifies signature, issuer, audience, nonce and at_hash before creating a server-only session", async () => {
    const now = 1_700_000_000_000;
    const config = oidcConfig();
    const store = new InMemoryConsoleAuthStore();
    const sources = createSourceProfileRegistry(config);
    const service = new OidcLoginService(config, store, sources);
    const redirect = await service.begin(sources.requireDefinition("primary"), "/en", now);
    const state = new URL(redirect.location).searchParams.get("state")!;
    const nonce = new URL(redirect.location).searchParams.get("nonce")!;
    const accessToken = "opaque-access-token";
    const { publicKey, privateKey } = await generateKeyPair("RS256", { extractable: true });
    const publicJwk = await exportJWK(publicKey);
    const atHash = createHash("sha256").update(accessToken, "ascii").digest().subarray(0, 16).toString("base64url");
    const idToken = await new SignJWT({
      nonce,
      name: "Alice",
      tenant_alias: "Tianjin",
      at_hash: atHash,
    })
      .setProtectedHeader({ alg: "RS256", kid: "key-1", typ: "JWT" })
      .setIssuer("https://id.example")
      .setAudience("flowweft-console")
      .setSubject("user-1")
      .setIssuedAt(Math.floor(now / 1_000))
      .setExpirationTime(Math.floor(now / 1_000) + 3_600)
      .sign(privateKey);
    requestPinnedJsonMock
      .mockResolvedValueOnce({
        access_token: accessToken,
        token_type: "Bearer",
        expires_in: 3_600,
        id_token: idToken,
      })
      .mockResolvedValueOnce({ keys: [{ ...publicJwk, kid: "key-1", alg: "RS256", use: "sig" }] });

    const completion = await service.complete(state, "authorization-code", now + 1_000);
    expect(completion.returnPath).toBe("/en");
    expect(completion.session).toMatchObject({
      subjectDisplayName: "Alice",
      tenantAlias: "Tianjin",
      sourceProfileId: "primary",
    });
    expect(JSON.stringify(completion.session)).not.toContain(accessToken);
    expect(completion.sessionId).not.toContain(accessToken);
    expect((await store.readSession(sha256Base64Url(completion.sessionId), now + 2_000))?.accessToken)
      .toBe(accessToken);
    expect(requestPinnedJsonMock).toHaveBeenCalledTimes(2);
  });
});

function oidcConfig(
  clientId = "flowweft-console",
  publicOrigin = "https://console.example",
  sessionTtlSeconds = "3600",
) {
  return parseConsoleServerConfig({
    NODE_ENV: "test",
    FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: publicOrigin,
    FLOWWEFT_CONSOLE_SESSION_TTL_SECONDS: sessionTtlSeconds,
    FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
    FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
      version: 1,
      profiles: [{
        id: "primary",
        displayName: "Primary",
        baseUrl: "https://flowweft.example",
        authenticationModes: ["OIDC_PKCE"],
        oidc: {
          issuer: "https://id.example",
          authorizationEndpoint: "https://id.example/authorize",
          tokenEndpoint: "https://id.example/token",
          jwksUri: "https://id.example/jwks",
          clientId,
          scopes: ["openid", "profile"],
          tenantAliasClaim: "tenant_alias",
        },
      }],
    }),
  });
}
