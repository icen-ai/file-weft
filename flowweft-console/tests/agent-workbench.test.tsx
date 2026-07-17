import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AgentWorkbench, AgentWorkbenchUnavailable } from "@/features/agent/AgentWorkbench";

const now = Date.UTC(2026, 6, 16, 9, 0, 0);
const budget = {
  maximumInputTokens: 8_000,
  maximumOutputTokens: 4_000,
  maximumModelCalls: 8,
  maximumToolCalls: 4,
  maximumDurationMillis: 120_000,
  maximumCostMicros: 500_000,
};
const usage = {
  inputTokens: 1_200,
  outputTokens: 640,
  modelCalls: 2,
  toolCalls: 0,
  durationMillis: 9_500,
  costMicros: 42_000,
  additionalUnits: {},
};
const citation = {
  id: "citation-1",
  documentId: "document-legal-1",
  documentVersionId: "version-3",
  evidenceId: "evidence-9",
  contentDigest: "a".repeat(64),
  startOffset: 12,
  endOffset: 80,
  pageNumber: 3,
  securityFilterReceiptDigest: "b".repeat(64),
  authorizationRevision: "acl-revision-42",
  authorizationExpiresAt: now + 60_000,
  evidenceDigest: "c".repeat(64),
  filteredAt: now,
};

describe("AgentWorkbench", () => {
  it("renders live conversation, durable run, authorized message and citation evidence without trust material", () => {
    const run = {
      id: "run-1",
      conversationId: "conversation-1",
      capabilityId: "knowledge.answer",
      status: "COMPLETED" as const,
      budget,
      usage,
      stateVersion: 7,
      createdAt: now - 20_000,
      updatedAt: now,
      deadlineAt: now + 100_000,
      failure: null,
    };
    const { container } = render(<AgentWorkbench
      locale="zh"
      conversations={{
        nextCursor: null,
        items: [{
          id: "conversation-1",
          title: "法律知识问答",
          latestRunStatus: "COMPLETED",
          stateVersion: 4,
          createdAt: now - 40_000,
          updatedAt: now,
        }],
      }}
      conversation={{
        summary: {
          id: "conversation-1",
          title: "法律知识问答",
          latestRunStatus: "COMPLETED",
          stateVersion: 4,
          createdAt: now - 40_000,
          updatedAt: now,
        },
        defaultCapabilityId: "knowledge.answer",
        defaultBudget: budget,
      }}
      runs={{ items: [run], nextCursor: null }}
      run={run}
      messages={{
        runId: "run-1",
        nextCursor: null,
        items: [{
          id: "message-1",
          runId: "run-1",
          sequence: 1,
          role: "ASSISTANT",
          authorizedDisplayText: "合同应按照当前有效版本进行复核。",
          citations: [citation],
          createdAt: now,
        }],
      }}
      events={{
        runId: "run-1",
        nextCursor: null,
        items: [{
          runId: "run-1",
          sequence: 2,
          occurredAt: now,
          type: "STATUS",
          stateVersion: 7,
          status: "COMPLETED",
          messageId: null,
          approvalRequestId: null,
          safeCode: null,
        }],
      }}
      citations={{ items: [citation], nextCursor: null }}
    />);

    expect(screen.getByRole("heading", { name: "Agent 对话工作台" })).toBeInTheDocument();
    expect(screen.getAllByText("法律知识问答")).toHaveLength(2);
    expect(screen.getByText("合同应按照当前有效版本进行复核。")).toBeInTheDocument();
    expect(screen.getByText("document-legal-1")).toBeInTheDocument();
    expect(screen.getByText("acl-revision-42")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/tenant-1|server-only-token|provider-secret|system prompt/u);
  });

  it("keeps hidden selections and unavailable capabilities fail-closed", () => {
    const { container, rerender } = render(<AgentWorkbench
      locale="en"
      conversations={{ items: [], nextCursor: null }}
      conversation={null}
      runs={null}
      run={null}
      messages={null}
      events={null}
      citations={null}
      selectedConversationUnavailable
      selectedRunUnavailable
    />);

    expect(screen.getByText(/hidden or temporarily unavailable/u)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/conversation-private-77|403|404|forbidden|not found/u);

    rerender(<AgentWorkbenchUnavailable locale="en" />);
    expect(screen.getByRole("heading", { name: "Agent capability is unavailable" })).toBeInTheDocument();
    expect(screen.getByText(/never falls back to browser-direct access/u)).toBeInTheDocument();
  });
});
