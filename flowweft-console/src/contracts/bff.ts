export type BffCapabilityState = "AVAILABLE" | "UNAVAILABLE" | "DEGRADED";

export interface SourceProfileSummary {
  readonly id: string;
  readonly displayName: string;
  readonly authenticationModes: readonly ("OIDC_PKCE" | "HOST_TOKEN_EXCHANGE")[];
  readonly state: BffCapabilityState;
}

export interface ConsoleSessionProjection {
  readonly subjectDisplayName: string;
  readonly tenantAlias: string;
  readonly sourceProfileId: string;
  readonly expiresAt: string;
  readonly capabilities: Readonly<Record<string, BffCapabilityState>>;
}

export interface CapabilitySnapshot {
  readonly revision: string;
  readonly capabilities: Readonly<Record<string, BffCapabilityState>>;
}

export interface ConsoleDocumentSummary {
  readonly id: string;
  readonly documentNumber: string;
  readonly title: string;
  readonly lifecycleState: string;
  readonly currentVersionId: string | null;
  readonly folderId: string | null;
  readonly createdTime: number;
  readonly updatedTime: number;
}

export interface ConsoleDocumentPage {
  readonly items: readonly ConsoleDocumentSummary[];
  readonly nextCursor: string | null;
  readonly total: number | null;
}

export interface ConsoleDocumentPageQuery {
  readonly cursor?: string;
  readonly limit?: number;
  readonly lifecycleState?: string;
  readonly folderId?: string;
}

export interface ConsoleDocumentVersion {
  readonly id: string;
  readonly versionNumber: string;
  readonly fileName: string;
  readonly contentLength: number;
  readonly contentType: string | null;
  readonly createdTime: number;
  readonly updatedTime: number;
}

export interface ConsoleDocumentDetail {
  readonly document: ConsoleDocumentSummary;
  readonly versions: readonly ConsoleDocumentVersion[];
}

export type ConsoleDoctorStatus = "HEALTHY" | "WARNING" | "ERROR" | "SKIPPED";

export interface ConsoleDoctorCheck {
  readonly checkerName: string;
  readonly status: ConsoleDoctorStatus;
  readonly reason: string;
  readonly repairSuggestion: string | null;
}

export interface ConsoleSystemDoctorReport {
  readonly status: ConsoleDoctorStatus;
  readonly checks: readonly ConsoleDoctorCheck[];
  readonly inspectedTime: number;
}

export interface ConsoleApprovalTask {
  readonly id: string;
  readonly workflowId: string;
  readonly state: string;
  readonly createdTime: number;
  readonly updatedTime: number;
  readonly assignedToCurrentUser: boolean;
}

export interface ConsoleApprovalInboxItem {
  readonly task: ConsoleApprovalTask;
  readonly document: ConsoleDocumentSummary;
  readonly workflowType: string;
  readonly workflowState: string;
  readonly actionableByCurrentUser: true;
}

export interface ConsoleApprovalInboxPage {
  readonly items: readonly ConsoleApprovalInboxItem[];
  readonly nextCursor: string | null;
  readonly total: number | null;
}

export interface ConsoleApprovalInboxQuery {
  readonly cursor?: string;
  readonly limit?: number;
}

export interface BffProblem {
  readonly code: "UNAUTHENTICATED" | "FORBIDDEN" | "UNAVAILABLE" | "INVALID_REQUEST" | "INTERNAL_ERROR";
  readonly message: string;
  readonly traceId?: string;
}
