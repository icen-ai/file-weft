import type { Locale } from "@/i18n/locale";

export type CapabilityPageId =
  | "documents"
  | "approvals"
  | "sync"
  | "doctor"
  | "audit"
  | "agent"
  | "toolApprovals"
  | "retrieval"
  | "evaluations"
  | "settings";

export type NavigationId = "dashboard" | CapabilityPageId;
export type NavigationGroupId = "work" | "assurance" | "intelligence" | "administration";

export interface NavigationItem {
  readonly id: NavigationId;
  readonly code: string;
  readonly segment: string;
}

export interface NavigationSection {
  readonly id: NavigationGroupId;
  readonly items: readonly NavigationItem[];
}

export const navigationSections: readonly NavigationSection[] = [
  {
    id: "work",
    items: [
      { id: "dashboard", code: "00", segment: "" },
      { id: "documents", code: "01", segment: "/documents" },
      { id: "approvals", code: "02", segment: "/approvals" },
      { id: "sync", code: "03", segment: "/sync" },
    ],
  },
  {
    id: "assurance",
    items: [
      { id: "doctor", code: "04", segment: "/doctor" },
      { id: "audit", code: "05", segment: "/audit" },
    ],
  },
  {
    id: "intelligence",
    items: [
      { id: "agent", code: "06", segment: "/agent" },
      { id: "toolApprovals", code: "07", segment: "/tool-approvals" },
      { id: "retrieval", code: "08", segment: "/retrieval" },
      { id: "evaluations", code: "09", segment: "/evaluations" },
    ],
  },
  {
    id: "administration",
    items: [{ id: "settings", code: "10", segment: "/settings" }],
  },
] as const;

export function localizePath(locale: Locale, segment: string): string {
  return `/${locale}${segment}`;
}
