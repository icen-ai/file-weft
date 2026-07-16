// @vitest-environment node

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { parseConsoleServerConfig } from "@/server/config/schema";
import { consoleOriginBindingDigest } from "@/server/security/ConsoleOriginBinding";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

describe("Source Profile credential binding", () => {
  it("is stable for presentation changes but changes for credential routing policy", () => {
    const original = profile({ displayName: "Primary" });
    const renamed = profile({ displayName: "主要来源" });
    const rerouted = profile({ displayName: "Primary", baseUrl: "https://other.example" });
    const widenedNetwork = profile({ displayName: "Primary", allowPrivateNetwork: true });

    expect(sourceProfileBindingDigest(original)).toMatch(/^[A-Za-z0-9_-]{43}$/u);
    expect(sourceProfileBindingDigest(renamed)).toBe(sourceProfileBindingDigest(original));
    expect(sourceProfileBindingDigest(rerouted)).not.toBe(sourceProfileBindingDigest(original));
    expect(sourceProfileBindingDigest(widenedNetwork)).not.toBe(sourceProfileBindingDigest(original));
  });

  it("changes when the same-origin cookie or expiry policy changes", () => {
    const original = consoleConfig("https://console.example", "3600");
    const differentPort = consoleConfig("https://console.example:8443", "3600");
    const shorterSession = consoleConfig("https://console.example", "900");
    expect(consoleOriginBindingDigest(differentPort)).not.toBe(consoleOriginBindingDigest(original));
    expect(consoleOriginBindingDigest(shorterSession)).not.toBe(consoleOriginBindingDigest(original));
  });
});

function profile(options: {
  readonly displayName: string;
  readonly baseUrl?: string;
  readonly allowPrivateNetwork?: boolean;
}) {
  return parseConsoleServerConfig({
    NODE_ENV: "test",
    FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
    FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
      version: 1,
      profiles: [{
        id: "primary",
        displayName: options.displayName,
        baseUrl: options.baseUrl ?? "https://flowweft.example",
        authenticationModes: ["HOST_TOKEN_EXCHANGE"],
        api: { allowPrivateNetwork: options.allowPrivateNetwork ?? false },
        hostTokenExchange: {
          endpointPath: "/internal/console/token-exchange",
          allowPrivateNetwork: false,
        },
      }],
    }),
  }).sourceProfiles[0]!;
}

function consoleConfig(publicOrigin: string, sessionTtlSeconds: string) {
  return parseConsoleServerConfig({
    NODE_ENV: "test",
    FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: publicOrigin,
    FLOWWEFT_CONSOLE_SESSION_TTL_SECONDS: sessionTtlSeconds,
  });
}
