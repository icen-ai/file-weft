import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApprovalInbox } from "@/features/approvals/ApprovalInbox";

describe("ApprovalInbox", () => {
  it("renders only safe actionable task context", () => {
    const { container } = render(<ApprovalInbox locale="en" page={{
      nextCursor: null,
      total: 1,
      items: [{
        task: {
          id: "task-1",
          workflowId: "workflow-1",
          state: "PENDING",
          createdTime: Date.UTC(2026, 6, 15, 7, 0, 0),
          updatedTime: Date.UTC(2026, 6, 15, 8, 0, 0),
          assignedToCurrentUser: true,
        },
        document: {
          id: "doc-1",
          documentNumber: "FW-001",
          title: "Knowledge policy",
          lifecycleState: "PENDING_REVIEW",
          currentVersionId: "version-1",
          folderId: null,
          createdTime: Date.UTC(2026, 6, 14),
          updatedTime: Date.UTC(2026, 6, 15, 8, 0, 0),
        },
        workflowType: "KNOWLEDGE_FILE",
        workflowState: "PENDING",
        actionableByCurrentUser: true,
      }],
    }} />);

    expect(screen.getByRole("heading", { name: "Approval task center" })).toBeInTheDocument();
    expect(screen.getByText("Knowledge policy")).toBeInTheDocument();
    expect(screen.getByText("Assigned to me")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/reviewer-99|server-only-token|secret-comment/u);
  });
});
