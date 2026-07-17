import { render, screen } from "@testing-library/react";
import { createElement } from "react";
import { describe, expect, it } from "vitest";
import { StatusBadge } from "@/components/ui/StatusBadge";

describe("StatusBadge", () => {
  it("announces the state and applies its semantic tone", () => {
    render(createElement(StatusBadge, { tone: "pending", children: "Contract required" }));

    const badge = screen.getByRole("status");
    expect(badge).toHaveTextContent("Contract required");
    expect(badge).toHaveClass("status-badge--pending");
    expect(badge.querySelector("[aria-hidden='true']")).toBeInTheDocument();
  });
});
