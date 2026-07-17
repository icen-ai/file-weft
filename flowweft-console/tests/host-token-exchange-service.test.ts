// @vitest-environment node

import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/server/security/PinnedJsonHttpClient", () => ({
  requestPinnedJson: vi.fn(),
}));

import { InMemoryConsoleAuthStore } from "@/server/auth/ConsoleAuthStore";
import { HostTokenExchangeError, HostTokenExchangeService } from "@/server/auth/HostTokenExchangeService";
import {
  ConsoleLoginRateLimitedError,
  InMemoryConsoleLoginAttemptLimiter,
} from "@/server/auth/LoginAttemptLimiter";
import { sha256Base64Url } from "@/server/auth/OidcCrypto";
import { parseConsoleServerConfig } from "@/server/config/schema";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";

const requestPinnedJsonMock = vi.mocked(requestPinnedJson);

describe("host token exchange compatibility login", () => {
  beforeEach(() => requestPinnedJsonMock.mockReset());

  it("posts credentials only to the configured pinned endpoint and keeps the access token server-side", async () => {
    const config = hostExchangeConfig();
    const store = new InMemoryConsoleAuthStore();
    const limiter = new InMemoryConsoleLoginAttemptLimiter();
    const service = new HostTokenExchangeService(config, store, limiter);
    const password = "correct horse battery staple";
    requestPinnedJsonMock.mockResolvedValueOnce({
      access_token: "host-access-token",
      token_type: "Bearer",
      expires_in: 1_800,
      subject_id: "user-7",
      subject_display_name: "Alice",
      tenant_alias: "Tianjin",
    });

    const completion = await service.exchange(config.sourceProfiles[0]!, {
      tenantAlias: "Tianjin",
      username: "alice@example.com",
      password,
    }, "/zh", 1_700_000_000_000);

    const request = requestPinnedJsonMock.mock.calls[0]?.[0];
    expect(request?.url).toBe("https://flowweft.example/internal/console/token-exchange");
    expect(request?.allowPrivateNetwork).toBe(false);
    const form = new URLSearchParams(request?.body);
    expect(form.get("tenant_alias")).toBe("Tianjin");
    expect(form.get("username")).toBe("alice@example.com");
    expect(form.get("password")).toBe(password);
    expect(JSON.stringify(completion)).not.toContain(password);
    expect(JSON.stringify(completion)).not.toContain("host-access-token");
    expect((await store.readSession(sha256Base64Url(completion.sessionId), 1_700_000_001_000))?.accessToken)
      .toBe("host-access-token");
  });

  it("rejects tenant substitution, keeps errors secret-free, and bounds repeated attempts", async () => {
    const config = hostExchangeConfig();
    const store = new InMemoryConsoleAuthStore();
    const limiter = new InMemoryConsoleLoginAttemptLimiter({ maximumAttempts: 2, windowMillis: 60_000 });
    const service = new HostTokenExchangeService(config, store, limiter);
    const password = "TOP-SECRET-PASSWORD";
    requestPinnedJsonMock.mockResolvedValue({
      access_token: "host-access-token",
      token_type: "Bearer",
      expires_in: 1_800,
      subject_id: "user-7",
      subject_display_name: "Alice",
      tenant_alias: "AnotherTenant",
    });

    for (let attempt = 0; attempt < 2; attempt += 1) {
      let caught: unknown;
      try {
        await service.exchange(config.sourceProfiles[0]!, {
          tenantAlias: "Tianjin",
          username: "alice@example.com",
          password,
        }, "/en", 10_000 + attempt);
      } catch (error) {
        caught = error;
      }
      expect(caught).toBeInstanceOf(HostTokenExchangeError);
      expect(JSON.stringify(caught)).not.toContain(password);
    }
    await expect(service.exchange(config.sourceProfiles[0]!, {
      tenantAlias: "Tianjin",
      username: "alice@example.com",
      password,
    }, "/en", 10_003)).rejects.toBeInstanceOf(ConsoleLoginRateLimitedError);
    expect(requestPinnedJsonMock).toHaveBeenCalledTimes(2);
  });
});

function hostExchangeConfig() {
  return parseConsoleServerConfig({
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
        hostTokenExchange: {
          endpointPath: "/internal/console/token-exchange",
          allowPrivateNetwork: false,
        },
      }],
    }),
  });
}
