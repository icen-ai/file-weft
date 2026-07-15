import type { CapabilityPageId } from "@/config/navigation";

export type CapabilityTone = "acid" | "amber" | "blue" | "coral";

export interface CapabilityDefinition {
  readonly marker: string;
  readonly ledger: string;
  readonly tone: CapabilityTone;
}

export const capabilityDefinitions: Record<CapabilityPageId, CapabilityDefinition> = {
  documents: { marker: "DOC", ledger: "FW10-042", tone: "acid" },
  approvals: { marker: "APR", ledger: "FW10-043", tone: "amber" },
  sync: { marker: "SYN", ledger: "FW10-043", tone: "blue" },
  doctor: { marker: "D/R", ledger: "FW10-043", tone: "acid" },
  audit: { marker: "AUD", ledger: "FW10-043", tone: "coral" },
  agent: { marker: "A/1", ledger: "FW10-044", tone: "acid" },
  toolApprovals: { marker: "T/A", ledger: "FW10-044", tone: "amber" },
  retrieval: { marker: "RET", ledger: "FW10-044", tone: "blue" },
  evaluations: { marker: "EVA", ledger: "FW10-044", tone: "coral" },
  settings: { marker: "SET", ledger: "FW10-041/043/044", tone: "acid" },
};
