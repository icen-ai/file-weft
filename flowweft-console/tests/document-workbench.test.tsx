import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DocumentWorkbench } from "@/features/documents/DocumentWorkbench";

describe("DocumentWorkbench", () => {
  it("renders a live filtered catalog and server-validated version dossier without credentials", () => {
    const updatedTime = Date.UTC(2026, 6, 16, 8, 0, 0);
    const { container } = render(<DocumentWorkbench
      locale="zh"
      page={{
        nextCursor: "opaque-next-page",
        total: 1,
        items: [{
          id: "document-1",
          documentNumber: "FW-001",
          title: "法律文件保留策略",
          lifecycleState: "PUBLISHED",
          currentVersionId: "version-2",
          folderId: "legal/contracts",
          createdTime: updatedTime - 20_000,
          updatedTime,
        }],
      }}
      query={{ lifecycleState: "PUBLISHED", folderId: "legal/contracts" }}
      selectedDocumentId="document-1"
      detail={{
        document: {
          id: "document-1",
          documentNumber: "FW-001",
          title: "法律文件保留策略",
          lifecycleState: "PUBLISHED",
          currentVersionId: "version-2",
          folderId: "legal/contracts",
          createdTime: updatedTime - 20_000,
          updatedTime,
        },
        versions: [{
          id: "version-2",
          versionNumber: "2.0",
          fileName: "retention-policy.pdf",
          contentLength: 2_048,
          contentType: "application/pdf",
          createdTime: updatedTime,
          updatedTime,
        }],
      }}
    />);

    expect(screen.getByRole("heading", { name: "文档工作台" })).toBeInTheDocument();
    expect(screen.getAllByText("法律文件保留策略")).toHaveLength(2);
    expect(screen.getByText("retention-policy.pdf")).toBeInTheDocument();
    expect(screen.getByText("2 KB")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "PUBLISHED" })).toBeInTheDocument();
    expect(container.querySelector('tr[data-selected="true"]')).not.toBeNull();
    expect(container.textContent).not.toMatch(/server-only-token|Bearer |tenant-secret/u);
  });

  it("keeps hidden and absent selections indistinguishable", () => {
    const { container } = render(<DocumentWorkbench
      locale="en"
      page={{ items: [], nextCursor: null, total: 0 }}
      selectedDocumentId="private-document-123456"
      selectionUnavailable
    />);

    expect(screen.getByText(/hidden or temporarily unavailable/u)).toBeInTheDocument();
    expect(container.textContent).not.toContain("private-document-123456");
    expect(container.textContent).not.toMatch(/forbidden|not found|403|404/u);
  });
});
