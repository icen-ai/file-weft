import { describe, expect, it } from "vitest";
import { navigationSections } from "@/config/navigation";
import { messages } from "@/i18n/messages";

describe("console navigation", () => {
  it("covers every phase-one product surface exactly once", () => {
    const ids = navigationSections.flatMap((section) => section.items.map((item) => item.id));
    expect(ids).toEqual([
      "dashboard",
      "documents",
      "approvals",
      "sync",
      "doctor",
      "audit",
      "agent",
      "toolApprovals",
      "retrieval",
      "evaluations",
      "settings",
    ]);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("has non-empty labels in both tested locales", () => {
    for (const locale of ["zh", "en"] as const) {
      for (const section of navigationSections) {
        expect(messages[locale].navGroups[section.id].trim()).not.toBe("");
        for (const item of section.items) {
          expect(messages[locale].nav[item.id].trim()).not.toBe("");
        }
      }
    }
  });

  it("keeps page information architecture in locale parity", () => {
    expect(Object.keys(messages.zh.pages).sort()).toEqual(Object.keys(messages.en.pages).sort());
    for (const page of Object.keys(messages.zh.pages) as (keyof typeof messages.zh.pages)[]) {
      expect(messages.zh.pages[page].cards).toHaveLength(3);
      expect(messages.en.pages[page].cards).toHaveLength(3);
      expect(messages.zh.pages[page].proofs).toHaveLength(3);
      expect(messages.en.pages[page].proofs).toHaveLength(3);
    }
  });
});
