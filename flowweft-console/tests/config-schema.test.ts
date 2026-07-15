import { describe, expect, it } from "vitest";
import { parseConsoleServerConfig } from "@/server/config/schema";

describe("console server configuration", () => {
  it("accepts opaque allowlisted profile IDs without an endpoint", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_DEFAULT_LOCALE: "en",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "tianjin-primary, archive.readonly,tianjin-primary",
      FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME: "__Host-flowweft_session",
      FLOWWEFT_CONSOLE_SECURE_COOKIES: "true",
    });

    expect(config.defaultLocale).toBe("en");
    expect(config.allowedSourceProfileIds).toEqual(["tianjin-primary", "archive.readonly"]);
    expect(config.sourceProfiles).toEqual([]);
    expect(config).not.toHaveProperty("endpoint");
    expect(Object.isFrozen(config.allowedSourceProfileIds)).toBe(true);
  });

  it("accepts strict server-only source definitions without exposing them as browser config", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "tianjin-primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
        version: 1,
        profiles: [{
          id: "tianjin-primary",
          displayName: "天津生产环境",
          baseUrl: "https://flowweft.internal.example",
          authenticationModes: ["OIDC_PKCE"],
        }],
      }),
    });

    expect(config.sourceProfiles).toEqual([{
      id: "tianjin-primary",
      displayName: "天津生产环境",
      baseUrl: "https://flowweft.internal.example",
      authenticationModes: ["OIDC_PKCE"],
    }]);
    expect(Object.isFrozen(config.sourceProfiles)).toBe(true);
    expect(Object.isFrozen(config.sourceProfiles[0]?.authenticationModes)).toBe(true);
  });

  it("rejects profile IDs outside the allowlist and unsafe production origins", () => {
    const profile = (id: string, baseUrl: string) => JSON.stringify({
      version: 1,
      profiles: [{ id, displayName: "Unsafe", baseUrl, authenticationModes: ["OIDC_PKCE"] }],
    });

    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "approved",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: profile("unapproved", "https://flowweft.example"),
    })).toThrow();
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "approved",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: profile("approved", "http://flowweft.example"),
    })).toThrow();
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "approved",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: profile("approved", "https://flowweft.example/hidden"),
    })).toThrow();
  });

  it("rejects URL-like source input", () => {
    expect(() => parseConsoleServerConfig({
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "https://internal.example",
    })).toThrow();
  });

  it("fails closed for insecure production cookies", () => {
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME: "flowweft_session",
      FLOWWEFT_CONSOLE_SECURE_COOKIES: "false",
    })).toThrow();
  });

  it("accepts an explicit same-origin OIDC profile and freezes its security policy", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK: "true",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
        version: 1,
        profiles: [{
          id: "primary",
          displayName: "Primary",
          baseUrl: "https://flowweft.example",
          authenticationModes: ["OIDC_PKCE"],
          oidc: {
            issuer: "https://id.example/realms/flowweft/",
            authorizationEndpoint: "https://id.example/realms/flowweft/authorize",
            tokenEndpoint: "https://id.example/realms/flowweft/token",
            jwksUri: "https://id.example/realms/flowweft/jwks",
            clientId: "flowweft-console",
            scopes: ["openid", "profile"],
            tenantAliasClaim: "tenant_alias",
          },
        }],
      }),
    });

    expect(config.publicOrigin).toBe("https://console.example");
    expect(config.sourceProfiles[0]?.oidc).toMatchObject({
      issuer: "https://id.example/realms/flowweft/",
      clientId: "flowweft-console",
      scopes: ["openid", "profile"],
      allowedAlgorithms: ["RS256", "PS256", "ES256"],
      allowPrivateNetwork: false,
    });
    expect(Object.isFrozen(config.sourceProfiles[0]?.oidc?.scopes)).toBe(true);
  });

  it("rejects cross-origin OIDC endpoints and implicit production process-local sessions", () => {
    const profile = {
      version: 1,
      profiles: [{
        id: "primary",
        displayName: "Primary",
        baseUrl: "https://flowweft.example",
        authenticationModes: ["OIDC_PKCE"],
        oidc: {
          issuer: "https://id.example",
          authorizationEndpoint: "https://id.example/authorize",
          tokenEndpoint: "https://attacker.example/token",
          jwksUri: "https://id.example/jwks",
          clientId: "flowweft-console",
          scopes: ["openid"],
          tenantAliasClaim: "tenant_alias",
        },
      }],
    };
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK: "true",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify(profile),
    })).toThrow();

    profile.profiles[0]!.oidc.tokenEndpoint = "https://id.example/token";
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify(profile),
    })).toThrow();
  });

  it("accepts only a canonical server-side host token exchange path and requires production session acknowledgement", () => {
    const profile = {
      version: 1,
      profiles: [{
        id: "primary",
        displayName: "Primary",
        baseUrl: "https://flowweft.example",
        authenticationModes: ["HOST_TOKEN_EXCHANGE"],
        hostTokenExchange: {
          endpointPath: "/internal/console/token-exchange",
          allowPrivateNetwork: true,
        },
      }],
    };
    const config = parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK: "true",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify(profile),
    });
    expect(config.sourceProfiles[0]?.hostTokenExchange).toEqual({
      endpointPath: "/internal/console/token-exchange",
      allowPrivateNetwork: true,
    });
    expect(Object.isFrozen(config.sourceProfiles[0]?.hostTokenExchange)).toBe(true);

    profile.profiles[0]!.hostTokenExchange.endpointPath = "/internal/../admin";
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK: "true",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify(profile),
    })).toThrow();
  });

  it("accepts encrypted shared Redis sessions without the single-replica acknowledgement", () => {
    const key = Buffer.alloc(32, 11).toString("base64url");
    const profile = JSON.stringify({
      version: 1,
      profiles: [{
        id: "primary",
        displayName: "Primary",
        baseUrl: "https://flowweft.example",
        authenticationModes: ["HOST_TOKEN_EXCHANGE"],
        hostTokenExchange: { endpointPath: "/internal/console/token-exchange" },
      }],
    });
    const config = parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: profile,
      FLOWWEFT_CONSOLE_REDIS_URL: "rediss://redis.example:6380/0",
      FLOWWEFT_CONSOLE_SESSION_ENCRYPTION_KEYS_JSON: JSON.stringify([{ id: "key_2026_07", key }]),
    });
    expect(config.redis).toMatchObject({
      keyPrefix: "flowweft:console:v1",
      encryptionKeys: [{ id: "key_2026_07", key }],
    });
    expect(Object.isFrozen(config.redis?.encryptionKeys)).toBe(true);

    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: profile,
      FLOWWEFT_CONSOLE_REDIS_URL: "redis://redis.example:6379/0",
      FLOWWEFT_CONSOLE_SESSION_ENCRYPTION_KEYS_JSON: JSON.stringify([{ id: "key_2026_07", key }]),
    })).toThrow();
    expect(() => parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: profile,
      FLOWWEFT_CONSOLE_REDIS_URL: "rediss://redis.example:6380/0",
    })).toThrow();
  });
});
