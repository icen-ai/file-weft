import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { parseConsoleServerConfig } from "@/server/config/schema";
import {
  createSourceProfileRegistry,
  SourceProfileNotConfiguredError,
} from "@/server/sources/SourceProfileRegistry";

describe("source profile registry", () => {
  it("projects only safe summaries and never the configured origin", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "production",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary,allowlist-only",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
        version: 1,
        profiles: [
          {
            id: "primary",
            displayName: "Primary source",
            baseUrl: "https://secret-origin.internal.example",
            authenticationModes: ["OIDC_PKCE", "HOST_TOKEN_EXCHANGE"],
          },
          {
            id: "allowlist-only",
            displayName: "Allowlist source",
            baseUrl: "https://allowlist.internal.example",
            authenticationModes: ["OIDC_PKCE"],
          },
        ],
      }),
    });
    const registry = createSourceProfileRegistry(config);
    const summaries = registry.listSummaries();

    expect(summaries).toEqual([
      {
        id: "primary",
        displayName: "Primary source",
        authenticationModes: [],
        state: "DEGRADED",
      },
      {
        id: "allowlist-only",
        displayName: "Allowlist source",
        authenticationModes: [],
        state: "DEGRADED",
      },
    ]);
    expect(JSON.stringify(summaries)).not.toContain("internal.example");
    expect(registry.requireDefinition("primary").baseUrl).toBe("https://secret-origin.internal.example");
  });

  it("keeps allowlist-only records unavailable and rejects unknown selections", () => {
    const config = parseConsoleServerConfig({
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "allowlist-only",
    });
    const registry = createSourceProfileRegistry(config);

    expect(registry.listSummaries()[0]).toMatchObject({
      id: "allowlist-only",
      state: "UNAVAILABLE",
      authenticationModes: [],
    });
    expect(() => registry.requireDefinition("allowlist-only")).toThrow(SourceProfileNotConfiguredError);
    expect(() => registry.requireDefinition("https://attacker.example")).toThrow();
  });

  it("marks only a fully configured OIDC profile available", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "test",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
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
            clientId: "flowweft-console",
            scopes: ["openid"],
            tenantAliasClaim: "tenant_alias",
          },
        }],
      }),
    });
    expect(createSourceProfileRegistry(config).listSummaries()[0]?.state).toBe("AVAILABLE");
  });

  it("advertises a configured host exchange without exposing its endpoint", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "test",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
      FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
        version: 1,
        profiles: [{
          id: "primary",
          displayName: "Primary",
          baseUrl: "https://flowweft.example",
          authenticationModes: ["HOST_TOKEN_EXCHANGE"],
          hostTokenExchange: { endpointPath: "/internal/console/token-exchange" },
        }],
      }),
    });
    const summary = createSourceProfileRegistry(config).listSummaries()[0];
    expect(summary).toMatchObject({
      state: "AVAILABLE",
      authenticationModes: ["HOST_TOKEN_EXCHANGE"],
    });
    expect(JSON.stringify(summary)).not.toContain("token-exchange");
  });
});
