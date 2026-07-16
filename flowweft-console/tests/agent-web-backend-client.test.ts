// @vitest-environment node

import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/server/security/PinnedJsonHttpClient", () => ({ requestPinnedJson: vi.fn() }));

import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import {
  AgentWebBackendClientError,
  readAgentCitationPage,
  readAgentConversationDetail,
  readAgentConversationPage,
  readAgentEventPage,
  readAgentMessagePage,
  readAgentRun,
  readAgentRunPage,
} from "@/server/dal/AgentWebBackendClient";
import { parseConsoleServerConfig } from "@/server/config/schema";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const requestPinnedJsonMock = vi.mocked(requestPinnedJson);
const now = Date.now();

describe("explicit FlowWeft Agent Web DAL", () => {
  beforeEach(() => requestPinnedJsonMock.mockReset());

  it("uses only the bound server session and fixed read routes while projecting minimum DTOs", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    const session = storedSession();
    requestPinnedJsonMock
      .mockResolvedValueOnce(ok({ items: [conversationSummary()], nextCursor: cursor("conversation-next") }))
      .mockResolvedValueOnce(ok({ summary: conversationSummary(), defaultCapabilityId: capability(), defaultBudget: budget() }))
      .mockResolvedValueOnce(ok({ items: [run()], nextCursor: null }))
      .mockResolvedValueOnce(ok(run()))
      .mockResolvedValueOnce(ok({
        runId: id("run-1"),
        items: [{
          messageId: id("message-1"),
          runId: id("run-1"),
          sequence: 1,
          role: "ASSISTANT",
          authorizedDisplayText: "Authorized answer.",
          citations: [citationEvidence()],
          createdAt: now,
        }],
        nextCursor: durableCursor("run-1", "message-next", 2),
      }))
      .mockResolvedValueOnce(ok({
        runId: id("run-1"),
        items: [{
          runId: id("run-1"),
          sequence: 2,
          occurredAt: now,
          type: { value: "STATUS" },
          stateVersion: 7,
          status: "COMPLETED",
          messageId: null,
          approvalRequestId: null,
          safeCode: null,
        }],
        nextCursor: null,
      }))
      .mockResolvedValueOnce(ok({ items: [citationEvidence()], nextCursor: null }));

    const conversations = await readAgentConversationPage(profile, session, { limit: 30 });
    const conversation = await readAgentConversationDetail(profile, session, "conversation-1");
    const runs = await readAgentRunPage(profile, session, "conversation-1", { limit: 25 });
    const selectedRun = await readAgentRun(profile, session, "run-1");
    const messages = await readAgentMessagePage(profile, session, "run-1", { limit: 10 });
    const events = await readAgentEventPage(profile, session, "run-1", { limit: 40 });
    const citations = await readAgentCitationPage(profile, session, "run-1", { limit: 30 });

    expect(conversations.nextCursor).toBe("conversation-next");
    expect(conversation.defaultCapabilityId).toBe("knowledge.answer");
    expect(runs.items[0]?.conversationId).toBe("conversation-1");
    expect(selectedRun.status).toBe("COMPLETED");
    expect(messages.items[0]?.authorizedDisplayText).toBe("Authorized answer.");
    expect(messages.nextCursor).toBe("message-next");
    expect(events.items[0]?.type).toBe("STATUS");
    expect(citations.items[0]?.documentId).toBe("document-1");
    expect(JSON.stringify({ conversations, conversation, runs, selectedRun, messages, events, citations }))
      .not.toMatch(/tenant-sensitive|authorization-decision-1|server-only-token/u);
    expect(requestPinnedJsonMock.mock.calls.map((call) => call[0].url)).toEqual([
      "https://flowweft.example/flowweft/v1/agent/conversations",
      "https://flowweft.example/flowweft/v1/agent/conversations/conversation-1",
      "https://flowweft.example/flowweft/v1/agent/conversations/conversation-1/runs",
      "https://flowweft.example/flowweft/v1/agent/runs/run-1",
      "https://flowweft.example/flowweft/v1/agent/runs/run-1/messages",
      "https://flowweft.example/flowweft/v1/agent/runs/run-1/events",
      "https://flowweft.example/flowweft/v1/agent/runs/run-1/citations",
    ]);
    expect(requestPinnedJsonMock.mock.calls.every((call) =>
      call[0].headers?.Authorization === "Bearer server-only-token" && call[0].method === "GET",
    )).toBe(true);
    expect(requestPinnedJsonMock.mock.calls[4]?.[0].query).toEqual({ limit: "10" });
    expect(Object.isFrozen(messages.items[0]?.citations)).toBe(true);
  });

  it("fails closed for profile confusion, path injection and cross-object or malformed responses", async () => {
    const profile = backendConfig().sourceProfiles[0]!;
    await expect(readAgentConversationPage(profile, { ...storedSession(), sourceProfileId: "other" }))
      .rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();

    await expect(readAgentRun(profile, storedSession(), "../other-tenant"))
      .rejects.toMatchObject({ code: "INVALID_REQUEST" });
    expect(requestPinnedJsonMock).not.toHaveBeenCalled();

    requestPinnedJsonMock.mockResolvedValueOnce(ok({ ...run(), runId: id("different-run") }));
    await expect(readAgentRun(profile, storedSession(), "run-1"))
      .rejects.toBeInstanceOf(AgentWebBackendClientError);

    requestPinnedJsonMock.mockResolvedValueOnce(ok({
      runId: id("run-1"),
      items: [{
        runId: id("run-1"),
        sequence: 1,
        occurredAt: now,
        type: { value: "STATUS" },
        stateVersion: 1,
        status: null,
        messageId: null,
        approvalRequestId: null,
        safeCode: null,
      }],
      nextCursor: null,
    }));
    await expect(readAgentEventPage(profile, storedSession(), "run-1"))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    requestPinnedJsonMock.mockResolvedValueOnce({ ...ok({ items: [], nextCursor: null }), providerSecret: "leak" });
    await expect(readAgentConversationPage(profile, storedSession()))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });

    requestPinnedJsonMock.mockResolvedValueOnce(ok({
      items: [{ ...citationEvidence(), authorizationExpiresAt: Date.now() - 1 }],
      nextCursor: null,
    }));
    await expect(readAgentCitationPage(profile, storedSession(), "run-1"))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
});

function ok(data: unknown) {
  return { code: "OK", data, replayed: false };
}

function id(value: string) {
  return { value };
}

function capability(value = "knowledge.answer") {
  return { value };
}

function cursor(token: string) {
  return { token };
}

function budget() {
  return {
    maximumInputTokens: 8_000,
    maximumOutputTokens: 4_000,
    maximumModelCalls: 8,
    maximumToolCalls: 4,
    maximumDurationMillis: 120_000,
    maximumCostMicros: 500_000,
  };
}

function usage() {
  return {
    inputTokens: 1_200,
    outputTokens: 640,
    modelCalls: 2,
    toolCalls: 0,
    durationMillis: 9_500,
    costMicros: 42_000,
    additionalUnits: {},
  };
}

function conversationSummary() {
  return {
    conversationId: id("conversation-1"),
    title: "Legal knowledge",
    latestRunStatus: "COMPLETED",
    stateVersion: 4,
    createdAt: now - 40_000,
    updatedAt: now,
  };
}

function run() {
  return {
    runId: id("run-1"),
    conversationId: id("conversation-1"),
    capabilityId: capability(),
    status: "COMPLETED",
    budget: budget(),
    usage: usage(),
    stateVersion: 7,
    createdAt: now - 20_000,
    updatedAt: now,
    deadlineAt: now + 100_000,
    messageCursor: null,
    eventCursor: null,
    failure: null,
  };
}

function durableCursor(runId: string, token: string, nextSequence: number) {
  return {
    runId: id(runId),
    nextSequence,
    cursor: cursor(token),
    issuedAt: now,
    expiresAt: now + 60_000,
  };
}

function citationEvidence() {
  return {
    citation: {
      citationId: id("citation-1"),
      tenantId: id("tenant-sensitive"),
      documentId: id("document-1"),
      documentVersionId: id("version-3"),
      evidenceId: id("evidence-9"),
      contentDigest: "a".repeat(64),
      startOffset: 12,
      endOffset: 80,
      pageNumber: 3,
    },
    securityFilterReceiptDigest: "b".repeat(64),
    authorizationDecisionId: id("authorization-decision-1"),
    authorizationRevision: "acl-revision-42",
    authorizationExpiresAt: now + 60_000,
    evidenceDigest: "c".repeat(64),
    filteredAt: now,
  };
}

function backendConfig() {
  return parseConsoleServerConfig({
    NODE_ENV: "test",
    FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS: "primary",
    FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON: JSON.stringify({
      version: 1,
      profiles: [{
        id: "primary",
        displayName: "Primary",
        baseUrl: "https://flowweft.example",
        authenticationModes: ["OIDC_PKCE"],
        api: { allowPrivateNetwork: true },
      }],
    }),
  });
}

function storedSession(): StoredConsoleSession {
  const profile = backendConfig().sourceProfiles[0]!;
  return Object.freeze({
    sessionIdDigest: "A".repeat(43),
    consoleOriginBindingDigest: "D".repeat(43),
    sourceProfileId: "primary",
    sourceProfileBindingDigest: sourceProfileBindingDigest(profile),
    subjectId: "user-1",
    subjectDisplayName: "Alice",
    tenantAlias: "Tianjin",
    accessToken: "server-only-token",
    tokenType: "Bearer",
    createdAtEpochMillis: 1_700_000_000_000,
    expiresAtEpochMillis: 1_700_003_600_000,
  });
}
