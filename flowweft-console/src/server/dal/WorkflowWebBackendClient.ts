import "server-only";

import { z } from "zod";
import type {
  ConsoleWorkflowComment,
  ConsoleWorkflowCommentPage,
  ConsoleWorkflowDefinitionDetail,
  ConsoleWorkflowDefinitionPage,
  ConsoleWorkflowDefinitionSummary,
  ConsoleWorkflowHistoryPage,
  ConsoleWorkflowInstance,
  ConsoleWorkflowPageQuery,
  ConsoleWorkflowSubject,
  ConsoleWorkflowTaskDetail,
  ConsoleWorkflowTaskFormSummary,
  ConsoleWorkflowTaskPage,
  ConsoleWorkflowTaskSummary,
} from "@/contracts/bff";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import type { ConsoleSourceProfileDefinition } from "@/server/config/schema";
import {
  PinnedJsonHttpError,
  requestPinnedJson,
} from "@/server/security/PinnedJsonHttpClient";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const safeInteger = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const epochMillis = z.number().int().min(0).max(8_640_000_000_000_000);
const sha256 = z.string().regex(/^[0-9a-f]{64}$/u);
// Raw definition/form documents remain server-side, but JSON escaping can nearly double their
// contractual UTF-8 size. Keep the exceptional read bounded without rejecting valid payloads.
const WORKFLOW_DOCUMENT_MAXIMUM_RESPONSE_BYTES = 4 * 1_024 * 1_024;
const workflowCode = z.string().min(1).max(96).regex(/^[A-Z][A-Z0-9_.:-]{0,95}$/u);
const opaqueCursor = z.string().min(1).max(1_024)
  .refine((value) => !/[\s\u0000-\u001f\u007f]/u.test(value) && !containsUnsafeUnicode(value));

function safeText(maximumBytes: number, allowLineBreaks = false) {
  return z.string().min(1).superRefine((value, context) => {
    const unsafeControls = allowLineBreaks
      ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u
      : /[\u0000-\u001f\u007f]/u;
    if (value.trim().length === 0 || Buffer.byteLength(value, "utf8") > maximumBytes ||
      unsafeControls.test(value) || containsUnsafeUnicode(value)) {
      context.addIssue({ code: "custom", message: "Unsafe Workflow text projection." });
    }
  });
}

const subjectSchema = z.object({
  type: safeText(64),
  id: safeText(512),
  revision: safeText(256),
  digest: sha256,
}).strict();

const definitionSummarySchema = z.object({
  id: safeText(512),
  key: safeText(256),
  version: safeText(128),
  status: workflowCode,
  title: safeText(512),
  contentDigest: sha256,
  recordVersion: safeInteger,
  createdAt: epochMillis,
  updatedAt: epochMillis,
}).strict().refine((value) => value.updatedAt >= value.createdAt);

const definitionPageSchema = z.object({
  items: z.array(definitionSummarySchema).max(200),
  nextCursor: opaqueCursor.nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.id)).size !== value.items.length) {
    context.addIssue({ code: "custom", message: "Workflow definition page contains duplicate identifiers." });
  }
});

const definitionDiagnosticSchema = z.object({
  code: workflowCode,
  severity: workflowCode,
  nodeId: safeText(512).nullable(),
}).strict();

const definitionDetailSchema = z.object({
  summary: definitionSummarySchema,
  codecId: safeText(128),
  codecVersion: safeText(128),
  definitionSource: safeText(1_048_576, true),
  sourceDigest: sha256,
  diagnostics: z.array(definitionDiagnosticSchema).max(500),
}).strict();

const taskSummarySchema = z.object({
  id: safeText(512),
  instanceId: safeText(512),
  name: safeText(512),
  state: workflowCode,
  recordVersion: safeInteger,
  createdAt: epochMillis,
  updatedAt: epochMillis,
  claimantIsCurrentUser: z.boolean(),
  actionableByCurrentUser: z.boolean(),
  dueAt: epochMillis.nullable(),
}).strict().refine((value) => value.updatedAt >= value.createdAt);

const taskPageSchema = z.object({
  items: z.array(taskSummarySchema).max(200),
  nextCursor: opaqueCursor.nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.id)).size !== value.items.length) {
    context.addIssue({ code: "custom", message: "Workflow task page contains duplicate identifiers." });
  }
});

const taskDetailSchema = z.object({
  task: taskSummarySchema,
  subject: subjectSchema,
  allowedActions: z.array(workflowCode).max(32),
  formId: safeText(512).nullable(),
  formVersion: safeText(128).nullable(),
}).strict().superRefine((value, context) => {
  if ((value.formId === null) !== (value.formVersion === null) ||
    new Set(value.allowedActions).size !== value.allowedActions.length) {
    context.addIssue({ code: "custom", message: "Workflow task detail is internally inconsistent." });
  }
});

const instanceSchema = z.object({
  id: safeText(512),
  definitionId: safeText(512),
  definitionVersion: safeText(128),
  definitionDigest: sha256,
  subject: subjectSchema,
  state: workflowCode,
  recordVersion: safeInteger,
  createdAt: epochMillis,
  updatedAt: epochMillis,
}).strict().refine((value) => value.updatedAt >= value.createdAt);

const historyEventSchema = z.object({
  sequence: safeInteger,
  eventType: workflowCode,
  state: workflowCode,
  occurredAt: epochMillis,
  performedByCurrentUser: z.boolean(),
  resourceId: safeText(512).nullable(),
  reasonCode: workflowCode.nullable(),
}).strict();

const historyPageSchema = z.object({
  items: z.array(historyEventSchema).max(200),
  nextCursor: opaqueCursor.nullable(),
}).strict().superRefine((value, context) => {
  if (value.items.some((item, index) => index > 0 && item.sequence <= value.items[index - 1]!.sequence)) {
    context.addIssue({ code: "custom", message: "Workflow history page sequences are not increasing." });
  }
});

const textCommentTokenSchema = z.object({
  kind: z.literal("TEXT"),
  text: safeText(8_192, true),
  principalType: z.null(),
  principalId: z.null(),
  displayNameSnapshot: z.null(),
}).strict();
const mentionCommentTokenSchema = z.object({
  kind: z.literal("MENTION"),
  text: z.null(),
  principalType: safeText(64),
  principalId: safeText(512),
  displayNameSnapshot: safeText(512),
}).strict();
const commentSchema = z.object({
  id: safeText(512),
  instanceId: safeText(512),
  revision: safeInteger,
  tokens: z.array(z.union([textCommentTokenSchema, mentionCommentTokenSchema])).max(256),
  authoredByCurrentUser: z.boolean(),
  createdAt: epochMillis,
  updatedAt: epochMillis,
}).strict().refine((value) => value.updatedAt >= value.createdAt);
const commentPageSchema = z.object({
  items: z.array(commentSchema).max(200),
  nextCursor: opaqueCursor.nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.id)).size !== value.items.length) {
    context.addIssue({ code: "custom", message: "Workflow comment page contains duplicate identifiers." });
  }
});

const taskFormSchema = z.object({
  formId: safeText(512),
  version: safeText(128),
  schemaDialect: safeText(128),
  schemaDocument: safeText(524_288, true),
  schemaDigest: sha256,
  uiSchemaDocument: safeText(524_288, true).nullable(),
  uiSchemaDigest: sha256.nullable(),
  projectedData: safeText(262_144, true).nullable(),
}).strict().superRefine((value, context) => {
  if ((value.uiSchemaDocument === null) !== (value.uiSchemaDigest === null)) {
    context.addIssue({ code: "custom", message: "Workflow task form is internally inconsistent." });
  }
});

const commandReceiptSchema = z.object({
  resourceType: workflowCode,
  resourceId: safeText(512),
  resourceVersion: safeInteger,
  state: workflowCode,
}).strict();

const workflowFailureCodeSchema = z.enum([
  "INVALID_REQUEST",
  "UNAUTHENTICATED",
  "FORBIDDEN",
  "NOT_FOUND",
  "METHOD_NOT_ALLOWED",
  "NOT_ACCEPTABLE",
  "UNSUPPORTED_MEDIA_TYPE",
  "CONFLICT",
  "PRECONDITION_REQUIRED",
  "PRECONDITION_FAILED",
  "CAPABILITY_UNSUPPORTED",
  "FEATURE_UNAVAILABLE",
  "CONTENT_UNAVAILABLE",
  "OUTCOME_UNKNOWN",
  "TOO_MANY_REQUESTS",
  "INTERNAL_ERROR",
]);
const workflowFailureEnvelopeSchema = z.object({
  code: workflowFailureCodeSchema,
  message: safeText(512),
  data: z.null(),
  error: z.object({
    code: workflowFailureCodeSchema,
    message: safeText(512),
  }).strict(),
  traceId: safeText(256).nullable().optional(),
}).strict().refine((value) =>
  value.code === value.error.code && value.message === value.error.message,
);

export type WorkflowTaskDecisionAction = "APPROVE" | "REJECT" | "REQUEST_CHANGES";

export class WorkflowWebBackendClientError extends Error {
  readonly code:
    | "UNAUTHENTICATED"
    | "UPSTREAM_UNAVAILABLE"
    | "INVALID_RESPONSE"
    | "INVALID_REQUEST"
    | "MUTATION_REJECTED"
    | "OUTCOME_UNKNOWN";

  constructor(code: WorkflowWebBackendClientError["code"]) {
    super(`FlowWeft Workflow Web request failed: ${code}.`);
    this.name = "WorkflowWebBackendClientError";
    this.code = code;
  }
}

/**
 * Executes the formal current-principal claim route. The caller must perform a fresh task read
 * first; the Workflow application boundary still performs the authoritative authorization.
 */
export async function claimWorkflowTask(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  taskId: string,
  expectedVersion: number,
  idempotencyKey: string,
): Promise<void> {
  const safeId = requirePathSegment(taskId);
  const receipt = await writeWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/tasks/${encodeURIComponent(safeId)}/claim`,
    {},
    expectedVersion,
    idempotencyKey,
  );
  requireTaskReceipt(receipt, safeId, expectedVersion);
}

export async function decideWorkflowTask(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  taskId: string,
  expectedVersion: number,
  idempotencyKey: string,
  action: WorkflowTaskDecisionAction,
): Promise<void> {
  const safeId = requirePathSegment(taskId);
  const safeAction = requireDecisionAction(action);
  const receipt = await writeWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/tasks/${encodeURIComponent(safeId)}/decisions`,
    { action: safeAction },
    expectedVersion,
    idempotencyKey,
  );
  requireTaskReceipt(receipt, safeId, expectedVersion);
}

/** Text-only Console comments deliberately exclude unresolved mention identifiers. */
export async function createWorkflowTextComment(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  instanceId: string,
  expectedVersion: number,
  idempotencyKey: string,
  text: string,
): Promise<void> {
  const safeId = requirePathSegment(instanceId);
  const safeComment = safeText(8_192, true).parse(text);
  const receipt = await writeWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/instances/${encodeURIComponent(safeId)}/comments`,
    { tokens: [{ kind: "TEXT", text: safeComment }] },
    expectedVersion,
    idempotencyKey,
  );
  if (receipt.resourceType !== "COMMENT") {
    throw new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
  let comments: ConsoleWorkflowCommentPage;
  try {
    // This is a single read-only reconciliation, never a retry of the comment command. The
    // generic receipt has no parent id, so success is only proven when the exact created comment
    // is visible on the fixed instance route under the same trusted session.
    comments = await readWorkflowCommentPage(profile, session, safeId, { limit: 50 });
  } catch {
    throw new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
  const comment = comments.items.find((item) => item.id === receipt.resourceId);
  if (!comment || comment.revision !== receipt.resourceVersion || !comment.authoredByCurrentUser ||
    comment.tokens.length !== 1 || comment.tokens[0]?.kind !== "TEXT" ||
    comment.tokens[0].text !== safeComment) {
    throw new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
}

export async function readWorkflowDefinitionPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  query: ConsoleWorkflowPageQuery = {},
): Promise<ConsoleWorkflowDefinitionPage> {
  const data = await readWorkflowData(
    profile, session, "/flowweft/v1/workflows/definitions", query, definitionPageSchema,
  );
  return Object.freeze({
    items: Object.freeze(data.items.map(projectDefinitionSummary)),
    nextCursor: data.nextCursor,
  });
}

export async function readWorkflowDefinition(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  definitionId: string,
): Promise<ConsoleWorkflowDefinitionDetail> {
  const safeId = requirePathSegment(definitionId);
  const data = await readWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/definitions/${encodeURIComponent(safeId)}`,
    undefined,
    definitionDetailSchema,
    WORKFLOW_DOCUMENT_MAXIMUM_RESPONSE_BYTES,
  );
  if (data.summary.id !== safeId) throw new WorkflowWebBackendClientError("INVALID_RESPONSE");
  return Object.freeze({
    summary: projectDefinitionSummary(data.summary),
    codecId: data.codecId,
    codecVersion: data.codecVersion,
    sourceDigest: data.sourceDigest,
    diagnostics: Object.freeze(data.diagnostics.map((diagnostic) => Object.freeze({ ...diagnostic }))),
  });
}

export async function readWorkflowTaskPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  query: ConsoleWorkflowPageQuery = {},
): Promise<ConsoleWorkflowTaskPage> {
  const data = await readWorkflowData(profile, session, "/flowweft/v1/workflows/tasks", query, taskPageSchema);
  return Object.freeze({ items: Object.freeze(data.items.map(projectTaskSummary)), nextCursor: data.nextCursor });
}

export async function readWorkflowTask(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  taskId: string,
): Promise<ConsoleWorkflowTaskDetail> {
  const safeId = requirePathSegment(taskId);
  const data = await readWorkflowData(
    profile, session, `/flowweft/v1/workflows/tasks/${encodeURIComponent(safeId)}`, undefined, taskDetailSchema,
  );
  if (data.task.id !== safeId) throw new WorkflowWebBackendClientError("INVALID_RESPONSE");
  return Object.freeze({
    task: projectTaskSummary(data.task),
    subject: projectSubject(data.subject),
    allowedActions: Object.freeze([...data.allowedActions]),
    formId: data.formId,
    formVersion: data.formVersion,
  });
}

export async function readWorkflowInstance(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  instanceId: string,
): Promise<ConsoleWorkflowInstance> {
  const safeId = requirePathSegment(instanceId);
  const data = await readWorkflowData(
    profile, session, `/flowweft/v1/workflows/instances/${encodeURIComponent(safeId)}`, undefined, instanceSchema,
  );
  if (data.id !== safeId) throw new WorkflowWebBackendClientError("INVALID_RESPONSE");
  return Object.freeze({ ...data, subject: projectSubject(data.subject) });
}

export async function readWorkflowHistoryPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  instanceId: string,
  query: ConsoleWorkflowPageQuery = {},
): Promise<ConsoleWorkflowHistoryPage> {
  const safeId = requirePathSegment(instanceId);
  const data = await readWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/instances/${encodeURIComponent(safeId)}/history`,
    query,
    historyPageSchema,
  );
  return Object.freeze({
    items: Object.freeze(data.items.map((item) => Object.freeze({ ...item }))),
    nextCursor: data.nextCursor,
  });
}

export async function readWorkflowCommentPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  instanceId: string,
  query: ConsoleWorkflowPageQuery = {},
): Promise<ConsoleWorkflowCommentPage> {
  const safeId = requirePathSegment(instanceId);
  const data = await readWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/instances/${encodeURIComponent(safeId)}/comments`,
    query,
    commentPageSchema,
  );
  if (data.items.some((comment) => comment.instanceId !== safeId)) {
    throw new WorkflowWebBackendClientError("INVALID_RESPONSE");
  }
  return Object.freeze({ items: Object.freeze(data.items.map(projectComment)), nextCursor: data.nextCursor });
}

export async function readWorkflowTaskForm(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  taskId: string,
): Promise<ConsoleWorkflowTaskFormSummary> {
  const safeId = requirePathSegment(taskId);
  const data = await readWorkflowData(
    profile,
    session,
    `/flowweft/v1/workflows/tasks/${encodeURIComponent(safeId)}/form`,
    undefined,
    taskFormSchema,
    WORKFLOW_DOCUMENT_MAXIMUM_RESPONSE_BYTES,
  );
  return Object.freeze({
    formId: data.formId,
    version: data.version,
    schemaDialect: data.schemaDialect,
    schemaDigest: data.schemaDigest,
    uiSchemaDigest: data.uiSchemaDigest,
    hasProjectedData: data.projectedData !== null,
  });
}

async function readWorkflowData<T>(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  path: string,
  query: ConsoleWorkflowPageQuery | undefined,
  schema: z.ZodType<T>,
  maximumResponseBytes?: number,
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
      ...(queryParameters === undefined ? {} : { query: queryParameters }),
      ...(maximumResponseBytes === undefined ? {} : { maximumResponseBytes }),
      timeoutMillis: 15_000,
      allowPrivateNetwork: profile.api?.allowPrivateNetwork ?? false,
    });
  } catch {
    throw new WorkflowWebBackendClientError("UPSTREAM_UNAVAILABLE");
  }
  const envelope = z.object({
    code: z.literal("OK"),
    message: safeText(512),
    data: schema,
    error: z.null(),
    traceId: safeText(256).nullable().optional(),
  }).strict().safeParse(rawResponse);
  if (!envelope.success) throw new WorkflowWebBackendClientError("INVALID_RESPONSE");
  return envelope.data.data;
}

async function writeWorkflowData(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  path: string,
  body: Readonly<Record<string, unknown>>,
  expectedVersion: number,
  idempotencyKey: string,
): Promise<z.infer<typeof commandReceiptSchema>> {
  requireSessionProfile(profile, session);
  const safeVersion = requireExpectedVersion(expectedVersion);
  const safeIdempotencyKey = requireIdempotencyKey(idempotencyKey);
  const endpoint = new URL(path, profile.baseUrl);
  let rawResponse: unknown;
  try {
    rawResponse = await requestPinnedJson({
      url: endpoint.toString(),
      method: "POST",
      body: JSON.stringify(body),
      headers: {
        Authorization: `Bearer ${session.accessToken}`,
        "Content-Type": "application/json",
        "Idempotency-Key": safeIdempotencyKey,
        "If-Match": `"fw-${safeVersion}"`,
      },
      timeoutMillis: 15_000,
      allowPrivateNetwork: profile.api?.allowPrivateNetwork ?? false,
      captureJsonErrorResponse: true,
    });
  } catch (failure) {
    throw classifyWorkflowWriteFailure(failure);
  }
  const envelope = z.object({
    code: z.literal("OK"),
    message: safeText(512),
    data: commandReceiptSchema,
    error: z.null(),
    traceId: safeText(256).nullable().optional(),
  }).strict().safeParse(rawResponse);
  if (!envelope.success) {
    // The command may already be committed even when its receipt is malformed.
    throw new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
  return envelope.data.data;
}

function classifyWorkflowWriteFailure(failure: unknown): WorkflowWebBackendClientError {
  if (!(failure instanceof PinnedJsonHttpError) || failure.statusCode === null ||
    failure.responseBody === null) {
    // Transport failure or a missing/malformed error receipt cannot prove the command outcome.
    return new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
  const envelope = workflowFailureEnvelopeSchema.safeParse(failure.responseBody);
  if (!envelope.success || expectedWorkflowFailureStatus(envelope.data.code) !== failure.statusCode) {
    return new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
  // INTERNAL_ERROR is produced when the Workflow application invocation throws. The invocation
  // may already have committed before that exception, so a syntactically valid 500 still cannot
  // prove rejection and must never invite a blind retry.
  return new WorkflowWebBackendClientError(
    envelope.data.code === "OUTCOME_UNKNOWN" || envelope.data.code === "INTERNAL_ERROR"
      ? "OUTCOME_UNKNOWN"
      : "MUTATION_REJECTED",
  );
}

function expectedWorkflowFailureStatus(code: z.infer<typeof workflowFailureCodeSchema>): number {
  switch (code) {
    case "INVALID_REQUEST": return 400;
    case "UNAUTHENTICATED": return 401;
    case "FORBIDDEN": return 403;
    case "NOT_FOUND": return 404;
    case "METHOD_NOT_ALLOWED": return 405;
    case "NOT_ACCEPTABLE": return 406;
    case "CONFLICT": return 409;
    case "PRECONDITION_FAILED": return 412;
    case "UNSUPPORTED_MEDIA_TYPE": return 415;
    case "PRECONDITION_REQUIRED": return 428;
    case "TOO_MANY_REQUESTS": return 429;
    case "CAPABILITY_UNSUPPORTED":
    case "FEATURE_UNAVAILABLE":
    case "CONTENT_UNAVAILABLE":
    case "OUTCOME_UNKNOWN": return 503;
    case "INTERNAL_ERROR": return 500;
  }
}

function requireTaskReceipt(
  receipt: z.infer<typeof commandReceiptSchema>,
  taskId: string,
  expectedVersion: number,
): void {
  if (receipt.resourceType !== "TASK" || receipt.resourceId !== taskId ||
    receipt.resourceVersion <= expectedVersion) {
    throw new WorkflowWebBackendClientError("OUTCOME_UNKNOWN");
  }
}

function requireExpectedVersion(value: number): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new WorkflowWebBackendClientError("INVALID_REQUEST");
  }
  return value;
}

function requireIdempotencyKey(value: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}$/u.test(value)) {
    throw new WorkflowWebBackendClientError("INVALID_REQUEST");
  }
  return value;
}

function requireDecisionAction(value: string): WorkflowTaskDecisionAction {
  if (value !== "APPROVE" && value !== "REJECT" && value !== "REQUEST_CHANGES") {
    throw new WorkflowWebBackendClientError("INVALID_REQUEST");
  }
  return value;
}

function projectPageQuery(query: ConsoleWorkflowPageQuery): Readonly<Record<string, string>> {
  const limit = query.limit ?? 25;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) {
    throw new WorkflowWebBackendClientError("INVALID_REQUEST");
  }
  const parameters: Record<string, string> = { limit: String(limit) };
  if (query.cursor !== undefined) parameters.cursor = requireOpaqueQuery(query.cursor);
  return Object.freeze(parameters);
}

function requireSessionProfile(profile: ConsoleSourceProfileDefinition, session: StoredConsoleSession): void {
  if (session.sourceProfileId !== profile.id ||
    session.sourceProfileBindingDigest !== sourceProfileBindingDigest(profile)) {
    throw new WorkflowWebBackendClientError("UNAUTHENTICATED");
  }
}

function requireOpaqueQuery(value: string): string {
  if (Buffer.byteLength(value, "utf8") < 1 || Buffer.byteLength(value, "utf8") > 1_024 ||
    /[\s\u0000-\u001f\u007f]/u.test(value) || containsUnsafeUnicode(value)) {
    throw new WorkflowWebBackendClientError("INVALID_REQUEST");
  }
  return value;
}

function requirePathSegment(value: string): string {
  if (Buffer.byteLength(value, "utf8") < 1 || Buffer.byteLength(value, "utf8") > 512 ||
    value === "." || value === ".." || value !== value.trim() ||
    /[\u0000-\u001f\u007f/\\?#]/u.test(value) || containsUnsafeUnicode(value)) {
    throw new WorkflowWebBackendClientError("INVALID_REQUEST");
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

function projectSubject(value: z.infer<typeof subjectSchema>): ConsoleWorkflowSubject {
  return Object.freeze({ ...value });
}

function projectDefinitionSummary(
  value: z.infer<typeof definitionSummarySchema>,
): ConsoleWorkflowDefinitionSummary {
  return Object.freeze({ ...value });
}

function projectTaskSummary(value: z.infer<typeof taskSummarySchema>): ConsoleWorkflowTaskSummary {
  return Object.freeze({ ...value });
}

function projectComment(value: z.infer<typeof commentSchema>): ConsoleWorkflowComment {
  return Object.freeze({
    id: value.id,
    revision: value.revision,
    authoredByCurrentUser: value.authoredByCurrentUser,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    tokens: Object.freeze(value.tokens.map((token) => Object.freeze(
      token.kind === "TEXT"
        ? { kind: "TEXT" as const, text: token.text }
        : { kind: "MENTION" as const, displayName: token.displayNameSnapshot },
    ))),
  });
}
