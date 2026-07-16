import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  WorkflowWorkbench,
  WorkflowWorkbenchUnavailable,
} from "@/features/workflow/WorkflowWorkbench";

const now = Date.UTC(2026, 6, 16, 10, 0, 0);
const digest = (character: string) => character.repeat(64);
const subject = { type: "EXPENSE", id: "expense-42", revision: "rev-9", digest: digest("c") };
const task = {
  id: "task-1",
  instanceId: "instance-1",
  name: "Finance review",
  state: "WAITING",
  recordVersion: 4,
  createdAt: now - 40_000,
  updatedAt: now,
  claimantIsCurrentUser: true,
  actionableByCurrentUser: true,
  dueAt: now + 86_400_000,
};
const definition = {
  id: "definition-1",
  key: "expense",
  version: "7",
  status: "PUBLISHED",
  title: "Expense approval",
  contentDigest: digest("a"),
  recordVersion: 5,
  createdAt: now - 100_000,
  updatedAt: now,
};

describe("WorkflowWorkbench", () => {
  it("renders a live task, related instance, safe timeline/comment mention and source-free definition", () => {
    const { container } = render(<WorkflowWorkbench
      locale="zh"
      tasks={{ items: [task], nextCursor: "task-next" }}
      definitions={{ items: [definition], nextCursor: "definition-next" }}
      selectedTaskId="task-1"
      selectedDefinitionId="definition-1"
      taskDetail={{
        task,
        subject,
        allowedActions: ["CLAIM", "APPROVE", "DELEGATE", "CREATE_COMMENT"],
        formId: "expense-form",
        formVersion: "3",
      }}
      instance={{
        id: "instance-1",
        definitionId: "definition-1",
        definitionVersion: "7",
        definitionDigest: digest("a"),
        subject,
        state: "ACTIVE",
        recordVersion: 11,
        createdAt: now - 80_000,
        updatedAt: now,
      }}
      history={{
        items: [{
          sequence: 8,
          eventType: "TASK_CREATED",
          state: "WAITING",
          occurredAt: now,
          performedByCurrentUser: false,
          resourceId: "task-1",
          reasonCode: null,
        }],
        nextCursor: "history-next",
      }}
      comments={{
        items: [{
          id: "comment-1",
          revision: 2,
          tokens: [{ kind: "TEXT", text: "请复核 " }, { kind: "MENTION", displayName: "王经理" }],
          authoredByCurrentUser: false,
          createdAt: now,
          updatedAt: now,
        }],
        nextCursor: "comment-next",
      }}
      form={{
        formId: "expense-form",
        version: "3",
        schemaDialect: "JSON_SCHEMA_2020_12",
        schemaDigest: digest("d"),
        uiSchemaDigest: null,
        hasProjectedData: true,
      }}
      definitionDetail={{
        summary: definition,
        codecId: "FLOWWEFT_JSON",
        codecVersion: "1",
        sourceDigest: digest("b"),
        diagnostics: [{ code: "SAFE_LINT", severity: "INFO", nodeId: "review" }],
      }}
      cursors={{
        task: "task-current",
        definition: "definition-current",
        history: "history-current",
        comment: "comment-current",
      }}
      mutationProtection={{
        csrfToken: "c".repeat(43),
        claimIdempotencyKey: "console-claim-1",
        decisionIdempotencyKeys: {
          APPROVE: "console-approve-1",
          REJECT: "console-reject-1",
          REQUEST_CHANGES: "console-changes-1",
        },
        commentIdempotencyKey: "console-comment-1",
      }}
      mutationResult="unknown"
    />);

    expect(screen.getByRole("heading", { name: "Workflow 流程工作台" })).toBeInTheDocument();
    expect(screen.getAllByText("Finance review")).toHaveLength(2);
    expect(screen.getByText("TASK_CREATED")).toBeInTheDocument();
    expect(screen.getByText("@王经理")).toBeInTheDocument();
    expect(screen.getByText("存在（未下发）")).toBeInTheDocument();
    expect(screen.getByText("SAFE_LINT")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "领取任务" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "通过" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发布评论" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("操作结果未确认");
    expect(container.querySelector('form[action="/api/bff/workflow/tasks/claim"]')).not.toBeNull();
    expect(container.querySelector('form[action="/api/bff/workflow/tasks/decide"]')).not.toBeNull();
    expect(container.querySelector('form[action="/api/bff/workflow/comments/create"]')).not.toBeNull();
    expect(container.querySelector('form[action*="delegate"]')).toBeNull();
    expect(container.querySelector('form[action="/api/bff/workflow/tasks/claim"] input[name="expectedTaskVersion"]'))
      .toHaveValue("4");
    expect(container.querySelector('form[action="/api/bff/workflow/comments/create"] input[name="expectedInstanceVersion"]'))
      .toHaveValue("11");
    expect(screen.getByRole("link", { name: /Finance review/u }).getAttribute("href"))
      .toContain("taskCursor=task-current");
    expect(screen.getByRole("link", { name: /Expense approval/u }).getAttribute("href"))
      .toContain("definitionCursor=definition-current");
    expect(screen.getByRole("link", { name: /下一页待办/u }).getAttribute("href"))
      .toMatch(/taskCursor=task-next.*historyCursor=history-current.*commentCursor=comment-current/u);
    expect(screen.getByRole("link", { name: /下一页定义/u }).getAttribute("href"))
      .toMatch(/definitionCursor=definition-next.*historyCursor=history-current.*commentCursor=comment-current/u);
    expect(container.textContent).not.toMatch(
      /tenant-sensitive|server-only-token|provider-secret|definitionSource|principal-sensitive|salary/u,
    );
  });

  it("keeps unavailable capabilities and hidden selections fail-closed", () => {
    const { container, rerender } = render(<WorkflowWorkbench
      locale="en"
      tasks={{ items: [task], nextCursor: "task-next" }}
      definitions={{ items: [definition], nextCursor: "definition-next" }}
      selectedTaskId="hidden-task"
      selectedDefinitionId="hidden-definition"
      taskDetail={null}
      instance={null}
      history={null}
      comments={null}
      form={null}
      definitionDetail={null}
      selectedTaskUnavailable
      selectedDefinitionUnavailable
    />);
    expect(screen.getByText(/selected task, related instance or current authority is unavailable/iu))
      .toBeInTheDocument();
    expect(screen.getByText(/selected definition is hidden/iu)).toBeInTheDocument();
    expect(container.textContent).not.toContain("hidden-task");
    expect(container.textContent).not.toContain("hidden-definition");
    const hrefs = Array.from(container.querySelectorAll("a[href]"), (link) => link.getAttribute("href")).join("\n");
    expect(hrefs).not.toContain("hidden-task");
    expect(hrefs).not.toContain("hidden-definition");

    rerender(<WorkflowWorkbenchUnavailable locale="en" />);
    expect(screen.getByRole("heading", { name: "Workflow capability is unavailable" })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/endpoint=https|tenant=|token=/u);
  });
});
