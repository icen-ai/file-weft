import "server-only";

import type { SourceProfileSummary } from "@/contracts/bff";
import type { ConsoleServerConfig } from "@/server/config/schema";

export type ConsoleReadinessCheckStatus = "PASS" | "WARNING" | "FAIL";

export interface ConsoleReadinessCheck {
  readonly code:
    | "CONFIGURATION_ACCEPTED"
    | "CONFIGURATION_REJECTED"
    | "SOURCE_PROFILES_READY"
    | "SOURCE_PROFILES_NOT_READY"
    | "PUBLIC_ORIGIN_BOUND"
    | "PUBLIC_ORIGIN_MISSING"
    | "SESSION_STORE_READY"
    | "SESSION_STORE_DEGRADED"
    | "SESSION_STORE_UNAVAILABLE"
    | "SESSION_STORE_POLICY_REJECTED";
  readonly status: ConsoleReadinessCheckStatus;
}

export interface ConsoleSecurityReadinessReport {
  readonly status: "UP" | "DOWN";
  readonly ready: boolean;
  readonly component: "flowweft-console";
  readonly checks: readonly ConsoleReadinessCheck[];
}

/**
 * Produces a public, secret-free readiness projection. It reports stable codes
 * only: profile IDs, origins, tenant aliases, Redis topology and parser errors
 * never cross the unauthenticated health boundary.
 */
export function assessConsoleSecurityReadiness(
  config: ConsoleServerConfig,
  profiles: readonly SourceProfileSummary[],
  sessionStoreAvailable = true,
): ConsoleSecurityReadinessReport {
  const profilesReady = config.allowedSourceProfileIds.length > 0 &&
    profiles.length === config.allowedSourceProfileIds.length &&
    new Set(profiles.map((profile) => profile.id)).size === profiles.length &&
    profiles.every((profile) => config.allowedSourceProfileIds.includes(profile.id) &&
      profile.state === "AVAILABLE");
  const checks: ConsoleReadinessCheck[] = [
    Object.freeze({ code: "CONFIGURATION_ACCEPTED", status: "PASS" }),
    Object.freeze({
      code: profilesReady ? "SOURCE_PROFILES_READY" : "SOURCE_PROFILES_NOT_READY",
      status: profilesReady ? "PASS" : "FAIL",
    }),
    Object.freeze({
      code: config.publicOrigin ? "PUBLIC_ORIGIN_BOUND" : "PUBLIC_ORIGIN_MISSING",
      status: config.publicOrigin ? "PASS" : "FAIL",
    }),
    sessionStoreReadinessCheck(config, sessionStoreAvailable),
  ];
  const ready = checks.every((check) => check.status !== "FAIL");
  return Object.freeze({
    status: ready ? "UP" : "DOWN",
    ready,
    component: "flowweft-console",
    checks: Object.freeze(checks),
  });
}

function sessionStoreReadinessCheck(
  config: ConsoleServerConfig,
  available: boolean,
): ConsoleReadinessCheck {
  if (!available) {
    return Object.freeze({ code: "SESSION_STORE_UNAVAILABLE", status: "FAIL" });
  }
  if (config.redis) {
    return Object.freeze({ code: "SESSION_STORE_READY", status: "PASS" });
  }
  if (config.environment !== "production") {
    return Object.freeze({ code: "SESSION_STORE_DEGRADED", status: "WARNING" });
  }
  return config.singleReplicaSessionStoreAcknowledged
    ? Object.freeze({ code: "SESSION_STORE_DEGRADED", status: "WARNING" })
    : Object.freeze({ code: "SESSION_STORE_POLICY_REJECTED", status: "FAIL" });
}

export function rejectedConsoleSecurityReadiness(): ConsoleSecurityReadinessReport {
  return Object.freeze({
    status: "DOWN",
    ready: false,
    component: "flowweft-console",
    checks: Object.freeze([
      Object.freeze({ code: "CONFIGURATION_REJECTED", status: "FAIL" }),
    ]),
  });
}
