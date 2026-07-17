import "server-only";

import { z } from "zod";
import type {
  ConsoleAgentBudget,
  ConsoleAgentCitationEvidence,
  ConsoleAgentCitationPage,
  ConsoleAgentConversationDetail,
  ConsoleAgentConversationPage,
  ConsoleAgentConversationSummary,
  ConsoleAgentEventPage,
  ConsoleAgentMessage,
  ConsoleAgentMessagePage,
  ConsoleAgentPageQuery,
  ConsoleAgentRun,
  ConsoleAgentRunEvent,
  ConsoleAgentRunPage,
  ConsoleAgentUsage,
} from "@/contracts/bff";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import type { ConsoleSourceProfileDefinition } from "@/server/config/schema";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const maximumSafeInteger = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const positiveSafeInteger = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER);
const epochMillis = z.number().int().min(0).max(8_640_000_000_000_000);
const positiveEpochMillis = z.number().int().min(1).max(8_640_000_000_000_000);
const sha256 = z.string().regex(/^[0-9a-f]{64}$/u);
const agentCode = z.string().min(1).max(128).regex(/^[A-Za-z0-9][A-Za-z0-9._:/-]*$/u);
const agentRunStatus = z.enum([
  "QUEUED",
  "RUNNING",
  "WAITING_APPROVAL",
  "WAITING_TOOL",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "EXPIRED",
]);

function safeText(maximumBytes: number, allowLineBreaks = false) {
  return z.string().min(1).superRefine((value, context) => {
    const unsafeControls = allowLineBreaks
      ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u
      : /[\u0000-\u001f\u007f]/u;
    if (value !== value.trim() || Buffer.byteLength(value, "utf8") > maximumBytes ||
      unsafeControls.test(value) || containsUnsafeUnicode(value)) {
      context.addIssue({ code: "custom", message: "Unsafe Agent text projection." });
    }
  });
}

const identifierSchema = z.object({ value: safeText(512) }).strict();
const capabilitySchema = z.object({ value: safeText(512) }).strict();
const cursorSchema = z.object({
  token: z.string().min(1).max(512).regex(/^[A-Za-z0-9][A-Za-z0-9._~:-]*$/u),
}).strict();
const budgetSchema = z.object({
  maximumInputTokens: positiveSafeInteger,
  maximumOutputTokens: positiveSafeInteger,
  maximumModelCalls: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
  maximumToolCalls: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  maximumDurationMillis: positiveSafeInteger,
  maximumCostMicros: maximumSafeInteger,
}).strict();
const usageSchema = z.object({
  inputTokens: maximumSafeInteger,
  outputTokens: maximumSafeInteger,
  modelCalls: maximumSafeInteger,
  toolCalls: maximumSafeInteger,
  durationMillis: maximumSafeInteger,
  costMicros: maximumSafeInteger,
  additionalUnits: z.record(agentCode, maximumSafeInteger),
}).strict().superRefine((value, context) => {
  if (Object.keys(value.additionalUnits).length > 64) {
    context.addIssue({ code: "custom", message: "Agent usage has too many dimensions." });
  }
});
const conversationSummarySchema = z.object({
  conversationId: identifierSchema,
  title: safeText(512),
  latestRunStatus: agentRunStatus.nullable(),
  stateVersion: maximumSafeInteger,
  createdAt: epochMillis,
  updatedAt: epochMillis,
}).strict().refine((value) => value.updatedAt >= value.createdAt);
const conversationPageSchema = z.object({
  items: z.array(conversationSummarySchema).max(200),
  nextCursor: cursorSchema.nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.conversationId.value)).size !== value.items.length) {
    context.addIssue({ code: "custom", message: "Agent conversation page contains duplicate identifiers." });
  }
});
const conversationDetailSchema = z.object({
  summary: conversationSummarySchema,
  defaultCapabilityId: capabilitySchema,
  defaultBudget: budgetSchema,
}).strict();
const durableCursorSchema = z.object({
  runId: identifierSchema,
  nextSequence: positiveSafeInteger,
  cursor: cursorSchema,
  issuedAt: epochMillis,
  expiresAt: positiveEpochMillis,
}).strict().refine((value) => value.expiresAt > value.issuedAt);
const runFailureSchema = z.object({
  category: z.object({ value: agentCode }).strict(),
  code: agentCode,
  safeMessage: safeText(16 * 1_024, true).nullable(),
}).strict();
const runSchema = z.object({
  runId: identifierSchema,
  conversationId: identifierSchema,
  capabilityId: capabilitySchema,
  status: agentRunStatus,
  budget: budgetSchema,
  usage: usageSchema,
  stateVersion: maximumSafeInteger,
  createdAt: epochMillis,
  updatedAt: epochMillis,
  deadlineAt: positiveEpochMillis,
  messageCursor: durableCursorSchema.nullable(),
  eventCursor: durableCursorSchema.nullable(),
  failure: runFailureSchema.nullable(),
}).strict().superRefine((value, context) => {
  if (value.updatedAt < value.createdAt || value.deadlineAt <= value.createdAt ||
    (value.status === "FAILED") !== (value.failure !== null) ||
    value.messageCursor !== null && value.messageCursor.runId.value !== value.runId.value ||
    value.eventCursor !== null && value.eventCursor.runId.value !== value.runId.value) {
    context.addIssue({ code: "custom", message: "Agent run projection is internally inconsistent." });
  }
});
const runPageSchema = z.object({
  items: z.array(runSchema).max(200),
  nextCursor: cursorSchema.nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.runId.value)).size !== value.items.length) {
    context.addIssue({ code: "custom", message: "Agent run page contains duplicate identifiers." });
  }
});
const citationSchema = z.object({
  citationId: identifierSchema,
  tenantId: identifierSchema,
  documentId: identifierSchema,
  documentVersionId: identifierSchema,
  evidenceId: identifierSchema,
  contentDigest: sha256,
  startOffset: maximumSafeInteger.nullable(),
  endOffset: positiveSafeInteger.nullable(),
  pageNumber: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).nullable(),
}).strict().superRefine((value, context) => {
  if ((value.startOffset === null) !== (value.endOffset === null) ||
    value.startOffset !== null && value.endOffset !== null && value.endOffset <= value.startOffset) {
    context.addIssue({ code: "custom", message: "Agent citation offsets are inconsistent." });
  }
});
const citationEvidenceSchema = z.object({
  citation: citationSchema,
  securityFilterReceiptDigest: sha256,
  authorizationDecisionId: identifierSchema,
  authorizationRevision: safeText(512),
  authorizationExpiresAt: positiveEpochMillis,
  evidenceDigest: sha256,
  filteredAt: epochMillis,
}).strict().refine((value) => value.authorizationExpiresAt > value.filteredAt);
const messageSchema = z.object({
  messageId: identifierSchema,
  runId: identifierSchema,
  sequence: positiveSafeInteger,
  role: z.enum(["USER", "ASSISTANT"]),
  authorizedDisplayText: safeText(32 * 1_024, true),
  citations: z.array(citationEvidenceSchema).max(1_000),
  createdAt: epochMillis,
}).strict().superRefine((value, context) => {
  if (value.role === "USER" && value.citations.length > 0 ||
    new Set(value.citations.map((entry) => entry.citation.citationId.value)).size !== value.citations.length) {
    context.addIssue({ code: "custom", message: "Agent visible message citations are inconsistent." });
  }
});
const durableMessagePageSchema = z.object({
  runId: identifierSchema,
  items: z.array(messageSchema).max(200),
  nextCursor: durableCursorSchema.nullable(),
}).strict().superRefine((value, context) => {
  if (value.nextCursor !== null && value.nextCursor.runId.value !== value.runId.value ||
    value.items.some((item) => item.runId.value !== value.runId.value) ||
    !hasStrictlyIncreasingSequences(value.items) ||
    value.nextCursor !== null && value.items.length > 0 &&
      value.nextCursor.nextSequence <= value.items[value.items.length - 1]!.sequence) {
    context.addIssue({ code: "custom", message: "Agent message page is internally inconsistent." });
  }
});
const eventSchema = z.object({
  runId: identifierSchema,
  sequence: positiveSafeInteger,
  occurredAt: epochMillis,
  type: z.object({ value: agentCode }).strict(),
  stateVersion: maximumSafeInteger,
  status: agentRunStatus.nullable(),
  messageId: identifierSchema.nullable(),
  approvalRequestId: identifierSchema.nullable(),
  safeCode: agentCode.nullable(),
}).strict().superRefine((value, context) => {
  if (value.type.value === "STATUS" && value.status === null ||
    value.type.value === "MESSAGE_AVAILABLE" && value.messageId === null ||
    value.type.value === "CONFIRMATION_REQUIRED" && value.approvalRequestId === null) {
    context.addIssue({ code: "custom", message: "Agent event projection is internally inconsistent." });
  }
});
const durableEventPageSchema = z.object({
  runId: identifierSchema,
  items: z.array(eventSchema).max(200),
  nextCursor: durableCursorSchema.nullable(),
}).strict().superRefine((value, context) => {
  if (value.nextCursor !== null && value.nextCursor.runId.value !== value.runId.value ||
    value.items.some((item) => item.runId.value !== value.runId.value) ||
    !hasStrictlyIncreasingSequences(value.items) ||
    value.nextCursor !== null && value.items.length > 0 &&
      value.nextCursor.nextSequence <= value.items[value.items.length - 1]!.sequence) {
    context.addIssue({ code: "custom", message: "Agent event page is internally inconsistent." });
  }
});
const citationPageSchema = z.object({
  items: z.array(citationEvidenceSchema).max(200),
  nextCursor: cursorSchema.nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.citation.citationId.value)).size !== value.items.length) {
    context.addIssue({ code: "custom", message: "Agent citation page contains duplicate identifiers." });
  }
});

export class AgentWebBackendClientError extends Error {
  readonly code: "UNAUTHENTICATED" | "UPSTREAM_UNAVAILABLE" | "INVALID_RESPONSE" | "INVALID_REQUEST";

  constructor(code: AgentWebBackendClientError["code"]) {
    super(`FlowWeft Agent Web request failed: ${code}.`);
    this.name = "AgentWebBackendClientError";
    this.code = code;
  }
}

export async function readAgentConversationPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  query: ConsoleAgentPageQuery = {},
): Promise<ConsoleAgentConversationPage> {
  const data = await readAgentData(profile, session, "/flowweft/v1/agent/conversations", query, conversationPageSchema);
  return Object.freeze({
    items: Object.freeze(data.items.map(projectConversationSummary)),
    nextCursor: data.nextCursor?.token ?? null,
  });
}

export async function readAgentConversationDetail(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  conversationId: string,
): Promise<ConsoleAgentConversationDetail> {
  const safeId = requirePathSegment(conversationId);
  const data = await readAgentData(
    profile,
    session,
    `/flowweft/v1/agent/conversations/${encodeURIComponent(safeId)}`,
    undefined,
    conversationDetailSchema,
  );
  if (data.summary.conversationId.value !== safeId) throw new AgentWebBackendClientError("INVALID_RESPONSE");
  return Object.freeze({
    summary: projectConversationSummary(data.summary),
    defaultCapabilityId: data.defaultCapabilityId.value,
    defaultBudget: projectBudget(data.defaultBudget),
  });
}

export async function readAgentRunPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  conversationId: string,
  query: ConsoleAgentPageQuery = {},
): Promise<ConsoleAgentRunPage> {
  const safeId = requirePathSegment(conversationId);
  const data = await readAgentData(
    profile,
    session,
    `/flowweft/v1/agent/conversations/${encodeURIComponent(safeId)}/runs`,
    query,
    runPageSchema,
  );
  if (data.items.some((run) => run.conversationId.value !== safeId)) {
    throw new AgentWebBackendClientError("INVALID_RESPONSE");
  }
  return Object.freeze({
    items: Object.freeze(data.items.map(projectRun)),
    nextCursor: data.nextCursor?.token ?? null,
  });
}

export async function readAgentRun(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  runId: string,
): Promise<ConsoleAgentRun> {
  const safeId = requirePathSegment(runId);
  const data = await readAgentData(
    profile,
    session,
    `/flowweft/v1/agent/runs/${encodeURIComponent(safeId)}`,
    undefined,
    runSchema,
  );
  if (data.runId.value !== safeId) throw new AgentWebBackendClientError("INVALID_RESPONSE");
  return projectRun(data);
}

export async function readAgentMessagePage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  runId: string,
  query: ConsoleAgentPageQuery = {},
): Promise<ConsoleAgentMessagePage> {
  const safeId = requirePathSegment(runId);
  const data = await readAgentData(
    profile,
    session,
    `/flowweft/v1/agent/runs/${encodeURIComponent(safeId)}/messages`,
    query,
    durableMessagePageSchema,
  );
  if (data.runId.value !== safeId) throw new AgentWebBackendClientError("INVALID_RESPONSE");
  return Object.freeze({
    runId: safeId,
    items: Object.freeze(data.items.map(projectMessage)),
    nextCursor: data.nextCursor?.cursor.token ?? null,
  });
}

export async function readAgentEventPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  runId: string,
  query: ConsoleAgentPageQuery = {},
): Promise<ConsoleAgentEventPage> {
  const safeId = requirePathSegment(runId);
  const data = await readAgentData(
    profile,
    session,
    `/flowweft/v1/agent/runs/${encodeURIComponent(safeId)}/events`,
    query,
    durableEventPageSchema,
  );
  if (data.runId.value !== safeId) throw new AgentWebBackendClientError("INVALID_RESPONSE");
  return Object.freeze({
    runId: safeId,
    items: Object.freeze(data.items.map(projectEvent)),
    nextCursor: data.nextCursor?.cursor.token ?? null,
  });
}

export async function readAgentCitationPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  runId: string,
  query: ConsoleAgentPageQuery = {},
): Promise<ConsoleAgentCitationPage> {
  const safeId = requirePathSegment(runId);
  const data = await readAgentData(
    profile,
    session,
    `/flowweft/v1/agent/runs/${encodeURIComponent(safeId)}/citations`,
    query,
    citationPageSchema,
  );
  return Object.freeze({
    items: Object.freeze(data.items.map(projectCitation)),
    nextCursor: data.nextCursor?.token ?? null,
  });
}

async function readAgentData<T>(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  path: string,
  query: ConsoleAgentPageQuery | undefined,
  schema: z.ZodType<T>,
): Promise<T> {
  requireSessionProfile(profile, session);
  const endpoint = new URL(path, profile.baseUrl);
  const queryParameters = query === undefined ? undefined : projectPageQuery(query);
  let rawResponse: unknown;
  try {
    rawResponse = await requestPinnedJson({
      url: endpoint.toString(),
      method: "GET",
      headers: { Authorization: `Bearer ${session.accessToken}` },
      ...(queryParameters ? { query: queryParameters } : {}),
      timeoutMillis: 15_000,
      allowPrivateNetwork: profile.api?.allowPrivateNetwork ?? false,
    });
  } catch {
    throw new AgentWebBackendClientError("UPSTREAM_UNAVAILABLE");
  }
  const envelope = z.object({
    code: z.literal("OK"),
    data: schema,
    replayed: z.boolean(),
  }).strict().safeParse(rawResponse);
  if (!envelope.success) throw new AgentWebBackendClientError("INVALID_RESPONSE");
  return envelope.data.data;
}

function projectPageQuery(query: ConsoleAgentPageQuery): Readonly<Record<string, string>> {
  const limit = query.limit ?? 25;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) {
    throw new AgentWebBackendClientError("INVALID_REQUEST");
  }
  const parameters: Record<string, string> = { limit: String(limit) };
  if (query.cursor !== undefined) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._~:-]{0,511}$/u.test(query.cursor)) {
      throw new AgentWebBackendClientError("INVALID_REQUEST");
    }
    parameters.cursor = query.cursor;
  }
  return Object.freeze(parameters);
}

function requireSessionProfile(profile: ConsoleSourceProfileDefinition, session: StoredConsoleSession): void {
  if (session.sourceProfileId !== profile.id ||
    session.sourceProfileBindingDigest !== sourceProfileBindingDigest(profile)) {
    throw new AgentWebBackendClientError("UNAUTHENTICATED");
  }
}

function requirePathSegment(value: string): string {
  if (Buffer.byteLength(value, "utf8") < 1 || Buffer.byteLength(value, "utf8") > 512 ||
    value !== value.trim() || /[\u0000-\u001f\u007f/\\?#]/u.test(value) || containsUnsafeUnicode(value)) {
    throw new AgentWebBackendClientError("INVALID_REQUEST");
  }
  return value;
}

function containsUnsafeUnicode(value: string): boolean {
  for (let offset = 0; offset < value.length;) {
    const codePoint = value.codePointAt(offset);
    if (codePoint === undefined) return true;
    if (codePoint >= 0xd800 && codePoint <= 0xdfff ||
      codePoint >= 0xfdd0 && codePoint <= 0xfdef ||
      (codePoint & 0xffff) === 0xfffe || (codePoint & 0xffff) === 0xffff ||
      /\p{Cf}/u.test(String.fromCodePoint(codePoint))) {
      return true;
    }
    offset += codePoint > 0xffff ? 2 : 1;
  }
  return false;
}

function hasStrictlyIncreasingSequences(items: readonly { readonly sequence: number }[]): boolean {
  return items.every((item, index) => index === 0 || item.sequence > items[index - 1]!.sequence);
}

function projectBudget(value: z.infer<typeof budgetSchema>): ConsoleAgentBudget {
  return Object.freeze({ ...value });
}

function projectUsage(value: z.infer<typeof usageSchema>): ConsoleAgentUsage {
  return Object.freeze({ ...value, additionalUnits: Object.freeze({ ...value.additionalUnits }) });
}

function projectConversationSummary(
  value: z.infer<typeof conversationSummarySchema>,
): ConsoleAgentConversationSummary {
  return Object.freeze({
    id: value.conversationId.value,
    title: value.title,
    latestRunStatus: value.latestRunStatus,
    stateVersion: value.stateVersion,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  });
}

function projectRun(value: z.infer<typeof runSchema>): ConsoleAgentRun {
  return Object.freeze({
    id: value.runId.value,
    conversationId: value.conversationId.value,
    capabilityId: value.capabilityId.value,
    status: value.status,
    budget: projectBudget(value.budget),
    usage: projectUsage(value.usage),
    stateVersion: value.stateVersion,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    deadlineAt: value.deadlineAt,
    failure: value.failure === null ? null : Object.freeze({
      category: value.failure.category.value,
      code: value.failure.code,
      safeMessage: value.failure.safeMessage,
    }),
  });
}

function projectCitation(value: z.infer<typeof citationEvidenceSchema>): ConsoleAgentCitationEvidence {
  if (value.authorizationExpiresAt <= Date.now()) {
    throw new AgentWebBackendClientError("INVALID_RESPONSE");
  }
  return Object.freeze({
    id: value.citation.citationId.value,
    documentId: value.citation.documentId.value,
    documentVersionId: value.citation.documentVersionId.value,
    evidenceId: value.citation.evidenceId.value,
    contentDigest: value.citation.contentDigest,
    startOffset: value.citation.startOffset,
    endOffset: value.citation.endOffset,
    pageNumber: value.citation.pageNumber,
    securityFilterReceiptDigest: value.securityFilterReceiptDigest,
    authorizationRevision: value.authorizationRevision,
    authorizationExpiresAt: value.authorizationExpiresAt,
    evidenceDigest: value.evidenceDigest,
    filteredAt: value.filteredAt,
  });
}

function projectMessage(value: z.infer<typeof messageSchema>): ConsoleAgentMessage {
  return Object.freeze({
    id: value.messageId.value,
    runId: value.runId.value,
    sequence: value.sequence,
    role: value.role,
    authorizedDisplayText: value.authorizedDisplayText,
    citations: Object.freeze(value.citations.map(projectCitation)),
    createdAt: value.createdAt,
  });
}

function projectEvent(value: z.infer<typeof eventSchema>): ConsoleAgentRunEvent {
  return Object.freeze({
    runId: value.runId.value,
    sequence: value.sequence,
    occurredAt: value.occurredAt,
    type: value.type.value,
    stateVersion: value.stateVersion,
    status: value.status,
    messageId: value.messageId?.value ?? null,
    approvalRequestId: value.approvalRequestId?.value ?? null,
    safeCode: value.safeCode,
  });
}
