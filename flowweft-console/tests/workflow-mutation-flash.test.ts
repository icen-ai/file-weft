// @vitest-environment node

import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { NextRequest } from "next/server";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import {
  createWorkflowMutationFlashWire,
  verifyWorkflowMutationFlashWire,
} from "@/server/security/WorkflowMutationSecurity";
import { proxy } from "@/proxy";

const now = Date.UTC(2026, 6, 16, 12, 0, 0);
const session: StoredConsoleSession = Object.freeze({
  sessionIdDigest: "A".repeat(43),
  consoleOriginBindingDigest: "B".repeat(43),
  sourceProfileId: "primary",
  sourceProfileBindingDigest: "C".repeat(43),
  subjectId: "user-secret",
  subjectDisplayName: "Alice",
  tenantAlias: "Tianjin",
  accessToken: "server-only-token",
  tokenType: "Bearer",
  createdAtEpochMillis: now - 1_000,
  expiresAtEpochMillis: now + 3_600_000,
});

describe("authenticated Workflow mutation flash", () => {
  it("binds the safe outcome to the session, profile, operation, targets and idempotency digest", () => {
    const wire = createWorkflowMutationFlashWire(session, {
      operation: "DECIDE_APPROVE",
      taskId: "task-1",
      instanceId: "instance-1",
      idempotencyKey: "console-approve-never-expose",
      outcome: "unknown",
    }, now);

    expect(verifyWorkflowMutationFlashWire(session, wire, now + 1_000)).toEqual({
      operation: "DECIDE_APPROVE",
      taskId: "task-1",
      instanceId: "instance-1",
      outcome: "unknown",
    });
    const payload = JSON.parse(Buffer.from(wire.split(".")[1]!, "base64url").toString("utf8")) as Record<string, unknown>;
    expect(payload.idempotencyKeyDigest).toMatch(/^[A-Za-z0-9_-]{43}$/u);
    expect(JSON.stringify(payload)).not.toMatch(/never-expose|server-only-token|user-secret|Alice|Tianjin/u);

    expect(verifyWorkflowMutationFlashWire({
      ...session, sourceProfileBindingDigest: "D".repeat(43),
    }, wire, now + 1_000)).toBeNull();
    expect(verifyWorkflowMutationFlashWire({
      ...session, accessToken: "another-server-token",
    }, wire, now + 1_000)).toBeNull();
  });

  it("rejects tampering and expiration", () => {
    const wire = createWorkflowMutationFlashWire(session, {
      operation: "CLAIM",
      taskId: "task-1",
      instanceId: "instance-1",
      idempotencyKey: "console-claim-1",
      outcome: "succeeded",
    }, now);
    const parts = wire.split(".");
    const tamperedPayload = `${parts[1]!.slice(0, -1)}${parts[1]!.endsWith("A") ? "B" : "A"}`;
    expect(verifyWorkflowMutationFlashWire(session, `${parts[0]}.${tamperedPayload}.${parts[2]}`, now + 1_000))
      .toBeNull();
    expect(verifyWorkflowMutationFlashWire(session, wire, now + 120_000)).toBeNull();
  });

  it("clears the HttpOnly flash on the approvals response", () => {
    const request = new NextRequest("https://console.example/zh/approvals?taskId=task-1", {
      headers: { cookie: "__Host-flowweft_session_workflow_flash=signed-wire" },
    });
    const response = proxy(request);
    const setCookie = response.headers.get("set-cookie") ?? "";

    expect(setCookie).toContain("__Host-flowweft_session_workflow_flash=");
    expect(setCookie).toMatch(/Max-Age=0/u);
    expect(setCookie).toMatch(/HttpOnly/u);
    expect(setCookie).toMatch(/SameSite=strict/iu);
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(response.headers.get("content-security-policy")).toContain("script-src");
    expect(response.headers.get("vary")).toBe("Cookie, Accept-Language");
  });

  it("preserves the global CSP without clearing Workflow flash cookies on other pages", () => {
    const request = new NextRequest("https://console.example/en/documents", {
      headers: { cookie: "__Host-flowweft_session_workflow_flash=signed-wire" },
    });
    const response = proxy(request);

    expect(response.headers.get("content-security-policy")).toContain("script-src");
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(response.headers.get("x-middleware-request-x-flowweft-locale")).toBe("en");
  });
});
