// @vitest-environment node

import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getRuntime: vi.fn(),
  readSession: vi.fn(),
  readTask: vi.fn(),
  readInstance: vi.fn(),
  claim: vi.fn(),
  decide: vi.fn(),
  createComment: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/server/auth/ConsoleAuthRuntime", () => ({ getConsoleAuthRuntime: mocks.getRuntime }));
vi.mock("@/server/auth/ConsoleSessionAccess", () => ({ readStoredConsoleSession: mocks.readSession }));
vi.mock("@/server/dal/WorkflowWebBackendClient", async (importOriginal) => ({
  ...await importOriginal<typeof import("@/server/dal/WorkflowWebBackendClient")>(),
  readWorkflowTask: mocks.readTask,
  readWorkflowInstance: mocks.readInstance,
  claimWorkflowTask: mocks.claim,
  decideWorkflowTask: mocks.decide,
  createWorkflowTextComment: mocks.createComment,
}));

import { NextRequest } from "next/server";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import { WorkflowWebBackendClientError } from "@/server/dal/WorkflowWebBackendClient";
import { handleWorkflowMutation } from "@/server/workflow/WorkflowMutationService";
import {
  verifyWorkflowMutationFlashWire,
  workflowMutationCsrfToken,
} from "@/server/security/WorkflowMutationSecurity";

const sessionId = "S".repeat(43);
const session: StoredConsoleSession = Object.freeze({
  sessionIdDigest: "A".repeat(43),
  consoleOriginBindingDigest: "B".repeat(43),
  sourceProfileId: "primary",
  sourceProfileBindingDigest: "C".repeat(43),
  subjectId: "user-1",
  subjectDisplayName: "Alice",
  tenantAlias: "Tianjin",
  accessToken: "server-only-token",
  tokenType: "Bearer",
  createdAtEpochMillis: 1_900_000_000_000,
  expiresAtEpochMillis: 1_900_003_600_000,
});
const profile = Object.freeze({
  id: "primary",
  displayName: "Primary",
  baseUrl: "https://flowweft.example",
  authenticationModes: ["OIDC_PKCE"] as const,
});
const subject = Object.freeze({ type: "EXPENSE", id: "expense-1", revision: "7", digest: "d".repeat(64) });

describe("fixed same-origin Workflow mutation service", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getRuntime.mockReturnValue({
      config: {
        publicOrigin: "https://console.example",
        defaultLocale: "zh",
        sessionCookieName: "__Host-flowweft_session",
        secureCookies: true,
      },
      sources: { requireDefinition: vi.fn(() => profile) },
    });
    mocks.readSession.mockResolvedValue(session);
    mocks.readTask.mockResolvedValue(task(["CLAIM", "APPROVE", "CREATE_COMMENT"]));
    mocks.readInstance.mockResolvedValue(instance());
    mocks.claim.mockResolvedValue(undefined);
    mocks.decide.mockResolvedValue(undefined);
    mocks.createComment.mockResolvedValue(undefined);
  });

  it("re-reads the task and forwards a claim with server-bound version and idempotency", async () => {
    const response = await handleWorkflowMutation(request(baseForm()), "CLAIM");

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe("https://console.example/zh/approvals?taskId=task-1");
    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(mocks.readTask).toHaveBeenCalledWith(profile, session, "task-1");
    expect(mocks.claim).toHaveBeenCalledWith(profile, session, "task-1", 4, "console-claim-1");
    expect(response.headers.get("location")).not.toMatch(/mutation|server-only-token|flowweft\.example/u);
    expect(readFlash(response)).toEqual({
      operation: "CLAIM", taskId: "task-1", instanceId: "instance-1", outcome: "succeeded",
    });
  });

  it("rejects cross-origin, invalid CSRF and stale intent before any upstream write", async () => {
    const crossOrigin = await handleWorkflowMutation(request(baseForm(), "https://attacker.example"), "CLAIM");
    expect(crossOrigin.headers.get("location")).toBe("https://console.example/zh/approvals");
    expect(readFlash(crossOrigin)).toBeNull();
    expect(mocks.readSession).not.toHaveBeenCalled();

    const invalidCsrfForm = baseForm();
    invalidCsrfForm.set("csrfToken", "x".repeat(43));
    const invalidCsrf = await handleWorkflowMutation(request(invalidCsrfForm), "CLAIM");
    expect(invalidCsrf.headers.get("location")).toBe("https://console.example/zh/approvals");
    expect(readFlash(invalidCsrf)).toBeNull();
    expect(mocks.readTask).not.toHaveBeenCalled();

    const staleForm = baseForm();
    staleForm.set("expectedTaskVersion", "3");
    const stale = await handleWorkflowMutation(request(staleForm), "CLAIM");
    expect(stale.headers.get("location")).toContain("taskId=task-1");
    expect(readFlash(stale)?.outcome).toBe("rejected");
    expect(mocks.claim).not.toHaveBeenCalled();
  });

  it("fails closed as unknown after a write starts and never reflects upstream details", async () => {
    mocks.claim.mockRejectedValueOnce(new Error("conflict for task-1 at https://flowweft.example?token=secret"));
    const response = await handleWorkflowMutation(request(baseForm()), "CLAIM");
    const location = response.headers.get("location") ?? "";

    expect(location).toBe("https://console.example/zh/approvals?taskId=task-1");
    expect(location).not.toMatch(/mutation|conflict|token|flowweft\.example/u);
    expect(readFlash(response)?.outcome).toBe("unknown");
  });

  it("keeps a formal deterministic upstream rejection distinct from an unknown outcome", async () => {
    mocks.claim.mockRejectedValueOnce(new WorkflowWebBackendClientError("MUTATION_REJECTED"));
    const response = await handleWorkflowMutation(request(baseForm()), "CLAIM");

    expect(response.headers.get("location")).toContain("taskId=task-1");
    expect(readFlash(response)?.outcome).toBe("rejected");

    mocks.claim.mockRejectedValueOnce(new WorkflowWebBackendClientError("OUTCOME_UNKNOWN"));
    const unknown = await handleWorkflowMutation(request(baseForm()), "CLAIM");
    expect(unknown.headers.get("location")).toContain("taskId=task-1");
    expect(readFlash(unknown)?.outcome).toBe("unknown");
  });

  it("maps only an exact freshly allowed decision to the formal decision route", async () => {
    const form = baseForm();
    form.set("action", "APPROVE");
    form.set("idempotencyKey", "console-approve-1");
    const response = await handleWorkflowMutation(request(form), "DECIDE");

    expect(response.headers.get("location")).toContain("taskId=task-1");
    expect(readFlash(response)?.operation).toBe("DECIDE_APPROVE");
    expect(mocks.decide).toHaveBeenCalledWith(
      profile,
      session,
      "task-1",
      4,
      "console-approve-1",
      "APPROVE",
    );

    form.set("action", "DELEGATE");
    const rejected = await handleWorkflowMutation(request(form), "DECIDE");
    expect(rejected.headers.get("location")).toBe("https://console.example/zh/approvals");
    expect(readFlash(rejected)).toBeNull();
    expect(mocks.decide).toHaveBeenCalledTimes(1);
  });

  it("derives a comment instance from the fresh task and binds both record versions", async () => {
    const form = baseForm();
    form.set("idempotencyKey", "console-comment-1");
    form.set("expectedInstanceVersion", "11");
    form.set("commentText", "Reviewed");
    const response = await handleWorkflowMutation(request(form), "CREATE_COMMENT");

    expect(response.headers.get("location")).toContain("taskId=task-1");
    expect(readFlash(response)).toEqual({
      operation: "CREATE_COMMENT", taskId: "task-1", instanceId: "instance-1", outcome: "succeeded",
    });
    expect(mocks.readInstance).toHaveBeenCalledWith(profile, session, "instance-1");
    expect(mocks.createComment).toHaveBeenCalledWith(
      profile,
      session,
      "instance-1",
      11,
      "console-comment-1",
      "Reviewed",
    );
  });
});

function readFlash(response: Response) {
  const wire = /(?:^|, )__Host-flowweft_session_workflow_flash=([^;]*)/u
    .exec(response.headers.get("set-cookie") ?? "")?.[1];
  return wire ? verifyWorkflowMutationFlashWire(session, wire) : null;
}

function request(form: URLSearchParams, origin = "https://console.example"): NextRequest {
  return new NextRequest("https://console.example/api/bff/workflow/tasks/claim", {
    method: "POST",
    headers: {
      origin,
      "sec-fetch-site": "same-origin",
      "content-type": "application/x-www-form-urlencoded",
      cookie: `__Host-flowweft_session=${sessionId}`,
    },
    body: form.toString(),
  });
}

function baseForm(): URLSearchParams {
  return new URLSearchParams({
    locale: "zh",
    taskId: "task-1",
    expectedTaskVersion: "4",
    csrfToken: workflowMutationCsrfToken(sessionId, session),
    idempotencyKey: "console-claim-1",
  });
}

function task(allowedActions: readonly string[]) {
  return {
    task: {
      id: "task-1", instanceId: "instance-1", name: "Review", state: "WAITING", recordVersion: 4,
      createdAt: 1, updatedAt: 2, claimantIsCurrentUser: false, actionableByCurrentUser: true, dueAt: null,
    },
    subject,
    allowedActions,
    formId: null,
    formVersion: null,
  };
}

function instance() {
  return {
    id: "instance-1", definitionId: "definition-1", definitionVersion: "1",
    definitionDigest: "e".repeat(64), subject, state: "ACTIVE", recordVersion: 11,
    createdAt: 1, updatedAt: 2,
  };
}
