// @vitest-environment node

import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/server/security/PinnedJsonHttpClient", () => ({ requestPinnedJson: vi.fn() }));

import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import { parseConsoleServerConfig } from "@/server/config/schema";
import {
  readWorkflowCommentPage,
  readWorkflowDefinition,
  readWorkflowDefinitionPage,
  readWorkflowHistoryPage,
  readWorkflowInstance,
  readWorkflowTask,
  readWorkflowTaskForm,
  readWorkflowTaskPage,
  WorkflowWebBackendClientError,
} from "@/server/dal/WorkflowWebBackendClient";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const requestPinnedJsonMock = vi.mocked(requestPinnedJson);
const now = Date.UTC(2026, 6, 16, 10, 0, 0);
const digest = (character: string) => character.repeat(64);

describe("explicit FlowWeft Workflow Web DAL", () => {
  beforeEach(() => requestPinnedJsonMock.mockReset());

  it("uses fixed server-only read routes and projects a minimum cross-object dossier", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    const session = storedSession();
    requestPinnedJsonMock
      .mockResolvedValueOnce(ok({ items: [definitionSummary()], nextCursor: "definition-next" }))
      .mockResolvedValueOnce(ok({
        summary: definitionSummary(),
        codecId: "FLOWWEFT_JSON",
        codecVersion: "1",
        definitionSource: "{\"secretExecutableNode\":true}",
        sourceDigest: digest("b"),
        diagnostics: [{ code: "SAFE_LINT", severity: "INFO", nodeId: "review" }],
      }))
      .mockResolvedValueOnce(ok({ items: [taskSummary()], nextCursor: "task-next" }))
      .mockResolvedValueOnce(ok({
        task: taskSummary(), subject: subject(), allowedActions: ["APPROVE"], formId: "expense-form", formVersion: "3",
      }))
      .mockResolvedValueOnce(ok(instance()))
      .mockResolvedValueOnce(ok({
        items: [{
          sequence: 8, eventType: "TASK_CREATED", state: "WAITING", occurredAt: now,
          performedByCurrentUser: false, resourceId: "task-1", reasonCode: null,
        }],
        nextCursor: "history-next",
      }))
      .mockResolvedValueOnce(ok({
        items: [{
          id: "comment-1", instanceId: "instance-1", revision: 2,
          tokens: [
            { kind: "TEXT", text: "Please review ", principalType: null, principalId: null, displayNameSnapshot: null },
            { kind: "MENTION", text: null, principalType: "USER", principalId: "principal-sensitive", displayNameSnapshot: "Alice" },
          ],
          authoredByCurrentUser: false, createdAt: now, updatedAt: now,
        }],
        nextCursor: null,
      }))
      .mockResolvedValueOnce(ok({
        formId: "expense-form", version: "3", schemaDialect: "JSON_SCHEMA_2020_12",
        schemaDocument: "{\"type\":\"object\"}", schemaDigest: digest("d"),
        uiSchemaDocument: null, uiSchemaDigest: null, projectedData: "{\"salary\":\"sensitive\"}",
      }));

    const definitions = await readWorkflowDefinitionPage(profile, session, { limit: 24 });
    const definition = await readWorkflowDefinition(profile, session, "definition-1");
    const tasks = await readWorkflowTaskPage(profile, session, { limit: 24 });
    const task = await readWorkflowTask(profile, session, "task-1");
    const selectedInstance = await readWorkflowInstance(profile, session, "instance-1");
    const history = await readWorkflowHistoryPage(profile, session, "instance-1", { limit: 50 });
    const comments = await readWorkflowCommentPage(profile, session, "instance-1", { limit: 30 });
    const form = await readWorkflowTaskForm(profile, session, "task-1");

    expect(definitions.nextCursor).toBe("definition-next");
    expect(definition.codecId).toBe("FLOWWEFT_JSON");
    expect(tasks.items[0]?.actionableByCurrentUser).toBe(true);
    expect(task.subject.id).toBe("expense-42");
    expect(selectedInstance.id).toBe("instance-1");
    expect(history.items[0]?.sequence).toBe(8);
    expect(comments.items[0]?.tokens).toEqual([
      { kind: "TEXT", text: "Please review " },
      { kind: "MENTION", displayName: "Alice" },
    ]);
    expect(form).toMatchObject({ formId: "expense-form", hasProjectedData: true });
    expect(JSON.stringify({ definition, comments, form })).not.toMatch(
      /secretExecutableNode|principal-sensitive|salary|server-only-token/u,
    );
    expect(requestPinnedJsonMock.mock.calls.map((call) => call[0].url)).toEqual([
      "https://flowweft.example/flowweft/v1/workflows/definitions",
      "https://flowweft.example/flowweft/v1/workflows/definitions/definition-1",
      "https://flowweft.example/flowweft/v1/workflows/tasks",
      "https://flowweft.example/flowweft/v1/workflows/tasks/task-1",
      "https://flowweft.example/flowweft/v1/workflows/instances/instance-1",
      "https://flowweft.example/flowweft/v1/workflows/instances/instance-1/history",
      "https://flowweft.example/flowweft/v1/workflows/instances/instance-1/comments",
      "https://flowweft.example/flowweft/v1/workflows/tasks/task-1/form",
    ]);
    expect(requestPinnedJsonMock.mock.calls.every((call) =>
      call[0].method === "GET" && call[0].headers?.Authorization === "Bearer server-only-token",
    )).toBe(true);
    expect(requestPinnedJsonMock.mock.calls.map((call) => call[0].maximumResponseBytes)).toEqual([
      undefined,
      4_194_304,
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      4_194_304,
    ]);
    expect(Object.isFrozen(comments.items[0]?.tokens)).toBe(true);
  });

  it("fails closed for profile confusion, path injection and malformed object ownership", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    await expect(readWorkflowTaskPage(profile, { ...storedSession(), sourceProfileId: "other" }))
      .rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    await expect(readWorkflowTask(profile, storedSession(), "../other-tenant"))
      .rejects.toMatchObject({ code: "INVALID_REQUEST" });
    await expect(readWorkflowTask(profile, storedSession(), "."))
      .rejects.toMatchObject({ code: "INVALID_REQUEST" });
    await expect(readWorkflowTask(profile, storedSession(), ".."))
      .rejects.toMatchObject({ code: "INVALID_REQUEST" });
    await expect(readWorkflowTaskPage(profile, storedSession(), { cursor: "bad cursor" }))
      .rejects.toMatchObject({ code: "INVALID_REQUEST" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();

    requestPinnedJsonMock.mockResolvedValueOnce(ok({
      task: { ...taskSummary(), id: "task-other" }, subject: subject(), allowedActions: [], formId: null, formVersion: null,
    }));
    await expect(readWorkflowTask(profile, storedSession(), "task-1"))
      .rejects.toBeInstanceOf(WorkflowWebBackendClientError);

    requestPinnedJsonMock.mockResolvedValueOnce(ok({
      items: [{
        id: "comment-1", instanceId: "instance-other", revision: 0,
        tokens: [{ kind: "TEXT", text: "Visible", principalType: null, principalId: null, displayNameSnapshot: null }],
        authoredByCurrentUser: true, createdAt: now, updatedAt: now,
      }],
      nextCursor: null,
    }));
    await expect(readWorkflowCommentPage(profile, storedSession(), "instance-1"))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    requestPinnedJsonMock.mockResolvedValueOnce({ ...ok({ items: [], nextCursor: null }), tenantId: "leak" });
    await expect(readWorkflowDefinitionPage(profile, storedSession()))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
});

function ok(data: unknown) {
  return { code: "OK", message: "OK", data, error: null, traceId: null };
}

function definitionSummary() {
  return {
    id: "definition-1", key: "expense", version: "7", status: "PUBLISHED", title: "Expense approval",
    contentDigest: digest("a"), recordVersion: 5, createdAt: now - 100_000, updatedAt: now,
  };
}

function subject() {
  return { type: "EXPENSE", id: "expense-42", revision: "rev-9", digest: digest("c") };
}

function taskSummary() {
  return {
    id: "task-1", instanceId: "instance-1", name: "Finance review", state: "WAITING",
    recordVersion: 4, createdAt: now - 40_000, updatedAt: now, claimantIsCurrentUser: true,
    actionableByCurrentUser: true, dueAt: now + 86_400_000,
  };
}

function instance() {
  return {
    id: "instance-1", definitionId: "definition-1", definitionVersion: "7",
    definitionDigest: digest("a"), subject: subject(), state: "ACTIVE", recordVersion: 11,
    createdAt: now - 80_000, updatedAt: now,
  };
}

function backendConfig() {
  return parseConsoleServerConfig({
    NODE_ENV: "test",
    FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
    FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
      version: 1,
      profiles: [{
        id: "primary", displayName: "Primary", baseUrl: "https://flowweft.example",
        authenticationModes: ["OIDC_PKCE"], api: { allowPrivateNetwork: true },
      }],
    }),
  });
}

function storedSession(): StoredConsoleSession {
  const profile = backendConfig().sourceProfiles[0]!;
  return Object.freeze({
    sessionIdDigest: "A".repeat(43), consoleOriginBindingDigest: "D".repeat(43), sourceProfileId: "primary",
    sourceProfileBindingDigest: sourceProfileBindingDigest(profile), subjectId: "user-1", subjectDisplayName: "Alice",
    tenantAlias: "Tianjin", accessToken: "server-only-token", tokenType: "Bearer",
    createdAtEpochMillis: 1_700_000_000_000, expiresAtEpochMillis: 1_700_003_600_000,
  });
}
