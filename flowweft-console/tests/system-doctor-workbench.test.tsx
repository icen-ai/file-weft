import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SystemDoctorWorkbench } from "@/features/doctor/SystemDoctorWorkbench";

describe("SystemDoctorWorkbench", () => {
  it("renders redacted live checks in Chinese without inventing topology", () => {
    const { container } = render(<SystemDoctorWorkbench locale="zh" report={{
      status: "ERROR",
      inspectedTime: Date.UTC(2026, 6, 15, 8, 0, 0),
      checks: [{
        checkerName: "storage",
        status: "ERROR",
        reason: "Storage check failed.",
        repairSuggestion: "Review authorized operational logs.",
      }],
    }} />);

    expect(screen.getByRole("heading", { name: "Doctor 运行台" })).toBeInTheDocument();
    expect(screen.getByText("storage")).toBeInTheDocument();
    expect(screen.getByText("Storage check failed.")).toBeInTheDocument();
    expect(container.textContent).not.toContain("server-only-token");
    expect(container.textContent).not.toContain("https://");
  });
});
