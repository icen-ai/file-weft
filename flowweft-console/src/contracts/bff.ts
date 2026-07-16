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

export type ConsoleAgentRunStatus =
  | "QUEUED"
  | "RUNNING"
  | "WAITING_APPROVAL"
  | "WAITING_TOOL"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"
  | "EXPIRED";

export interface ConsoleAgentPageQuery {
  readonly cursor?: string;
  readonly limit?: number;
}

export interface ConsoleAgentBudget {
  readonly maximumInputTokens: number;
  readonly maximumOutputTokens: number;
  readonly maximumModelCalls: number;
  readonly maximumToolCalls: number;
  readonly maximumDurationMillis: number;
  readonly maximumCostMicros: number;
}

export interface ConsoleAgentUsage {
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly modelCalls: number;
  readonly toolCalls: number;
  readonly durationMillis: number;
  readonly costMicros: number;
  readonly additionalUnits: Readonly<Record<string, number>>;
}

export interface ConsoleAgentConversationSummary {
  readonly id: string;
  readonly title: string;
  readonly latestRunStatus: ConsoleAgentRunStatus | null;
  readonly stateVersion: number;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface ConsoleAgentConversationPage {
  readonly items: readonly ConsoleAgentConversationSummary[];
  readonly nextCursor: string | null;
}

export interface ConsoleAgentConversationDetail {
  readonly summary: ConsoleAgentConversationSummary;
  readonly defaultCapabilityId: string;
  readonly defaultBudget: ConsoleAgentBudget;
}

export interface ConsoleAgentRunFailure {
  readonly category: string;
  readonly code: string;
  readonly safeMessage: string | null;
}

export interface ConsoleAgentRun {
  readonly id: string;
  readonly conversationId: string;
  readonly capabilityId: string;
  readonly status: ConsoleAgentRunStatus;
  readonly budget: ConsoleAgentBudget;
  readonly usage: ConsoleAgentUsage;
  readonly stateVersion: number;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly deadlineAt: number;
  readonly failure: ConsoleAgentRunFailure | null;
}

export interface ConsoleAgentRunPage {
  readonly items: readonly ConsoleAgentRun[];
  readonly nextCursor: string | null;
}

export interface ConsoleAgentCitationEvidence {
  readonly id: string;
  readonly documentId: string;
  readonly documentVersionId: string;
  readonly evidenceId: string;
  readonly contentDigest: string;
  readonly startOffset: number | null;
  readonly endOffset: number | null;
  readonly pageNumber: number | null;
  readonly securityFilterReceiptDigest: string;
  readonly authorizationRevision: string;
  readonly authorizationExpiresAt: number;
  readonly evidenceDigest: string;
  readonly filteredAt: number;
}

export interface ConsoleAgentMessage {
  readonly id: string;
  readonly runId: string;
  readonly sequence: number;
  readonly role: "USER" | "ASSISTANT";
  readonly authorizedDisplayText: string;
  readonly citations: readonly ConsoleAgentCitationEvidence[];
  readonly createdAt: number;
}

export interface ConsoleAgentMessagePage {
  readonly runId: string;
  readonly items: readonly ConsoleAgentMessage[];
  readonly nextCursor: string | null;
}

export interface ConsoleAgentRunEvent {
  readonly runId: string;
  readonly sequence: number;
  readonly occurredAt: number;
  readonly type: string;
  readonly stateVersion: number;
  readonly status: ConsoleAgentRunStatus | null;
  readonly messageId: string | null;
  readonly approvalRequestId: string | null;
  readonly safeCode: string | null;
}

export interface ConsoleAgentEventPage {
  readonly runId: string;
  readonly items: readonly ConsoleAgentRunEvent[];
  readonly nextCursor: string | null;
}

export interface ConsoleAgentCitationPage {
  readonly items: readonly ConsoleAgentCitationEvidence[];
  readonly nextCursor: string | null;
}

export interface ConsoleWorkflowPageQuery {
  readonly cursor?: string;
  readonly limit?: number;
}

export interface ConsoleWorkflowDefinitionSummary {
  readonly id: string;
  readonly key: string;
  readonly version: string;
  readonly status: string;
  readonly title: string;
  readonly contentDigest: string;
  readonly recordVersion: number;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface ConsoleWorkflowDefinitionPage {
  readonly items: readonly ConsoleWorkflowDefinitionSummary[];
  readonly nextCursor: string | null;
}

export interface ConsoleWorkflowDefinitionDiagnostic {
  readonly code: string;
  readonly severity: string;
  readonly nodeId: string | null;
}

/** Definition source remains in the trusted backend and never enters the browser projection. */
export interface ConsoleWorkflowDefinitionDetail {
  readonly summary: ConsoleWorkflowDefinitionSummary;
  readonly codecId: string;
  readonly codecVersion: string;
  readonly sourceDigest: string;
  readonly diagnostics: readonly ConsoleWorkflowDefinitionDiagnostic[];
}

export interface ConsoleWorkflowTaskSummary {
  readonly id: string;
  readonly instanceId: string;
  readonly name: string;
  readonly state: string;
  readonly recordVersion: number;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly claimantIsCurrentUser: boolean;
  readonly actionableByCurrentUser: boolean;
  readonly dueAt: number | null;
}

export interface ConsoleWorkflowTaskPage {
  readonly items: readonly ConsoleWorkflowTaskSummary[];
  readonly nextCursor: string | null;
}

export interface ConsoleWorkflowSubject {
  readonly type: string;
  readonly id: string;
  readonly revision: string;
  readonly digest: string;
}

export interface ConsoleWorkflowTaskDetail {
  readonly task: ConsoleWorkflowTaskSummary;
  readonly subject: ConsoleWorkflowSubject;
  readonly allowedActions: readonly string[];
  readonly formId: string | null;
  readonly formVersion: string | null;
}

export interface ConsoleWorkflowInstance {
  readonly id: string;
  readonly definitionId: string;
  readonly definitionVersion: string;
  readonly definitionDigest: string;
  readonly subject: ConsoleWorkflowSubject;
  readonly state: string;
  readonly recordVersion: number;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface ConsoleWorkflowHistoryEvent {
  readonly sequence: number;
  readonly eventType: string;
  readonly state: string;
  readonly occurredAt: number;
  readonly performedByCurrentUser: boolean;
  readonly resourceId: string | null;
  readonly reasonCode: string | null;
}

export interface ConsoleWorkflowHistoryPage {
  readonly items: readonly ConsoleWorkflowHistoryEvent[];
  readonly nextCursor: string | null;
}

export type ConsoleWorkflowCommentToken =
  | { readonly kind: "TEXT"; readonly text: string }
  | { readonly kind: "MENTION"; readonly displayName: string };

export interface ConsoleWorkflowComment {
  readonly id: string;
  readonly revision: number;
  readonly tokens: readonly ConsoleWorkflowCommentToken[];
  readonly authoredByCurrentUser: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface ConsoleWorkflowCommentPage {
  readonly items: readonly ConsoleWorkflowComment[];
  readonly nextCursor: string | null;
}

/** Form documents and projected values are intentionally omitted from the read-only Console. */
export interface ConsoleWorkflowTaskFormSummary {
  readonly formId: string;
  readonly version: string;
  readonly schemaDialect: string;
  readonly schemaDigest: string;
  readonly uiSchemaDigest: string | null;
  readonly hasProjectedData: boolean;
}

export interface BffProblem {
  readonly code: "UNAUTHENTICATED" | "FORBIDDEN" | "UNAVAILABLE" | "INVALID_REQUEST" | "INTERNAL_ERROR";
  readonly message: string;
  readonly traceId?: string;
}
