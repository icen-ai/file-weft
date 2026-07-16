// @vitest-environment node

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { parseConsoleServerConfig } from "@/server/config/schema";
import {
  assessConsoleSecurityReadiness,
  rejectedConsoleSecurityReadiness,
} from "@/server/diagnostics/ConsoleSecurityReadiness";
import { createSourceProfileRegistry } from "@/server/sources/SourceProfileRegistry";

describe("Console security readiness", () => {
  it("is ready only for a complete administrator-bound source policy", () => {
    const config = configuredConsole();
    const report = assessConsoleSecurityReadiness(
      config,
      createSourceProfileRegistry(config).listSummaries(),
    );

    expect(report).toMatchObject({ status: "UP", ready: true });
    expect(report.checks.map((check) => check.code)).toContain("SOURCE_PROFILES_READY");
    expect(JSON.stringify(report)).not.toMatch(/primary|flowweft\.example|console\.example|tenant/iu);
  });

  it("fails readiness for allowlist-only profiles without exposing identifiers", () => {
    const config = parseConsoleServerConfig({
      NODE_ENV: "test",
      FLOWWEFT_CONSOLE_PUBLIC_ORIGIN: "https://console.example",
      FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "sensitive-internal-source",
    });
    const report = assessConsoleSecurityReadiness(
      config,
      createSourceProfileRegistry(config).listSummaries(),
    );

    expect(report).toMatchObject({ status: "DOWN", ready: false });
    expect(report.checks).toContainEqual({ code: "SOURCE_PROFILES_NOT_READY", status: "FAIL" });
    expect(JSON.stringify(report)).not.toContain("sensitive-internal-source");
  });

  it("collapses rejected configuration into one safe diagnostic code", () => {
    const report = rejectedConsoleSecurityReadiness();
    expect(report).toEqual({
      status: "DOWN",
      ready: false,
      component: "flowweft-console",
      checks: [{ code: "CONFIGURATION_REJECTED", status: "FAIL" }],
    });
  });

  it("fails closed when the declared session store is unavailable", () => {
    const config = configuredConsole();
    const report = assessConsoleSecurityReadiness(
      config,
      createSourceProfileRegistry(config).listSummaries(),
      false,
    );
    expect(report).toMatchObject({ status: "DOWN", ready: false });
    expect(report.checks).toContainEqual({ code: "SESSION_STORE_UNAVAILABLE", status: "FAIL" });
  });
});

function configuredConsole() {
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
