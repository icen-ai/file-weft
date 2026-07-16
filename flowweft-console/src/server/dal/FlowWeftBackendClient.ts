import "server-only";

import { z } from "zod";
import type {
  ConsoleApprovalInboxPage,
  ConsoleApprovalInboxQuery,
  ConsoleDocumentPage,
  ConsoleDocumentPageQuery,
  ConsoleDoctorStatus,
  ConsoleSystemDoctorReport,
} from "@/contracts/bff";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import type { ConsoleSourceProfileDefinition } from "@/server/config/schema";
import { requestPinnedJson } from "@/server/security/PinnedJsonHttpClient";
import { sourceProfileBindingDigest } from "@/server/sources/SourceProfileBinding";

const safeText = (maximumLength: number) => z.string().min(1).max(maximumLength)
  .regex(/^[^\u0000-\u001f\u007f]+$/u);
const optionalOpaqueText = (maximumLength: number) => z.string().min(1).max(maximumLength)
  .regex(/^[^\s\u0000-\u001f\u007f]+$/u).nullable();

const documentSummarySchema = z.object({
  id: safeText(128),
  documentNumber: safeText(128),
  title: safeText(512),
  lifecycleState: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/u),
  createdTime: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  updatedTime: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  currentVersionId: optionalOpaqueText(128),
  folderId: safeText(512).nullable(),
}).strict().refine((value) => value.updatedTime >= value.createdTime);

const documentPageSchema = z.object({
  items: z.array(documentSummarySchema).max(100),
  nextCursor: optionalOpaqueText(512),
  total: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.id)).size !== value.items.length ||
    value.total !== null && value.total < value.items.length) {
    context.addIssue({ code: "custom", message: "Document page is internally inconsistent." });
  }
});

const successEnvelopeSchema = z.object({
  code: z.literal("OK"),
  message: safeText(512),
  data: documentPageSchema,
  error: z.null(),
  traceId: safeText(128).nullable().optional(),
}).strict();

const doctorStatusSchema = z.enum(["HEALTHY", "WARNING", "ERROR", "SKIPPED"]);
const doctorCheckSchema = z.object({
  checkerName: safeText(128),
  status: doctorStatusSchema,
  reason: safeText(512),
  repairSuggestion: safeText(1_000).nullable(),
}).strict();
const systemDoctorReportSchema = z.object({
  status: doctorStatusSchema,
  checks: z.array(doctorCheckSchema).max(128),
  inspectedTime: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
}).strict().superRefine((value, context) => {
  if (new Set(value.checks.map((check) => check.checkerName)).size !== value.checks.length ||
    aggregateDoctorStatus(value.checks.map((check) => check.status)) !== value.status) {
    context.addIssue({ code: "custom", message: "System Doctor report is internally inconsistent." });
  }
});
const systemDoctorEnvelopeSchema = z.object({
  code: z.literal("OK"),
  message: safeText(512),
  data: systemDoctorReportSchema,
  error: z.null(),
  traceId: safeText(128).nullable().optional(),
}).strict();

const approvalTaskSchema = z.object({
  id: safeText(128),
  workflowId: safeText(128),
  state: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/u),
  createdTime: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  updatedTime: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  assignedToCurrentUser: z.boolean(),
}).strict().refine((value) => value.updatedTime >= value.createdTime);
const approvalInboxItemSchema = z.object({
  task: approvalTaskSchema,
  document: documentSummarySchema,
  workflowType: safeText(64),
  workflowState: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/u),
  actionableByCurrentUser: z.literal(true),
}).strict();
const approvalInboxPageSchema = z.object({
  items: z.array(approvalInboxItemSchema).max(100),
  nextCursor: optionalOpaqueText(512),
  total: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.items.map((item) => item.task.id)).size !== value.items.length ||
    value.total !== null && value.total < value.items.length) {
    context.addIssue({ code: "custom", message: "Approval inbox page is internally inconsistent." });
  }
});
const approvalInboxEnvelopeSchema = z.object({
  code: z.literal("OK"),
  message: safeText(512),
  data: approvalInboxPageSchema,
  error: z.null(),
  traceId: safeText(128).nullable().optional(),
}).strict();

export class FlowWeftBackendClientError extends Error {
  readonly code: "UNAUTHENTICATED" | "UPSTREAM_UNAVAILABLE" | "INVALID_RESPONSE" | "INVALID_REQUEST";

  constructor(code: FlowWeftBackendClientError["code"]) {
    super(`FlowWeft backend request failed: ${code}.`);
    this.name = "FlowWeftBackendClientError";
    this.code = code;
  }
}

export async function readDocumentPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  query: ConsoleDocumentPageQuery = {},
): Promise<ConsoleDocumentPage> {
  requireSessionProfile(profile, session);
  const endpoint = new URL("/fileweft/v1/documents", profile.baseUrl);
  const queryParameters: Record<string, string> = {};
  if (query.cursor !== undefined) {
    queryParameters.cursor = requireOpaqueQuery(query.cursor, 512);
  }
  const limit = query.limit ?? 20;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    throw new FlowWeftBackendClientError("INVALID_REQUEST");
  }
  queryParameters.limit = String(limit);
  if (query.lifecycleState !== undefined) {
    if (!/^[A-Z][A-Z0-9_]{0,63}$/u.test(query.lifecycleState)) {
      throw new FlowWeftBackendClientError("INVALID_REQUEST");
    }
    queryParameters.lifecycleState = query.lifecycleState;
  }
  if (query.folderId !== undefined) {
    queryParameters.folderId = requireOpaqueQuery(query.folderId, 512);
  }

  let rawResponse: unknown;
  try {
    rawResponse = await requestPinnedJson({
      url: endpoint.toString(),
      method: "GET",
      headers: { Authorization: `Bearer ${session.accessToken}` },
      query: queryParameters,
      timeoutMillis: 10_000,
      allowPrivateNetwork: profile.api?.allowPrivateNetwork ?? false,
    });
  } catch {
    throw new FlowWeftBackendClientError("UPSTREAM_UNAVAILABLE");
  }
  const response = successEnvelopeSchema.safeParse(rawResponse);
  if (!response.success) {
    throw new FlowWeftBackendClientError("INVALID_RESPONSE");
  }
  return Object.freeze({
    items: Object.freeze(response.data.data.items.map((item) => Object.freeze({ ...item }))),
    nextCursor: response.data.data.nextCursor,
    total: response.data.data.total,
  });
}

export async function readSystemDoctorReport(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
): Promise<ConsoleSystemDoctorReport> {
  requireSessionProfile(profile, session);
  const endpoint = new URL("/fileweft/v1/doctor", profile.baseUrl);
  let rawResponse: unknown;
  try {
    rawResponse = await requestPinnedJson({
      url: endpoint.toString(),
      method: "GET",
      headers: { Authorization: `Bearer ${session.accessToken}` },
      timeoutMillis: 15_000,
      allowPrivateNetwork: profile.api?.allowPrivateNetwork ?? false,
    });
  } catch {
    throw new FlowWeftBackendClientError("UPSTREAM_UNAVAILABLE");
  }
  const response = systemDoctorEnvelopeSchema.safeParse(rawResponse);
  if (!response.success) {
    throw new FlowWeftBackendClientError("INVALID_RESPONSE");
  }
  return Object.freeze({
    status: response.data.data.status,
    checks: Object.freeze(response.data.data.checks.map((check) => Object.freeze({ ...check }))),
    inspectedTime: response.data.data.inspectedTime,
  });
}

export async function readApprovalInboxPage(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  query: ConsoleApprovalInboxQuery = {},
): Promise<ConsoleApprovalInboxPage> {
  requireSessionProfile(profile, session);
  const endpoint = new URL("/fileweft/v1/workflows/tasks", profile.baseUrl);
  const queryParameters: Record<string, string> = {};
  if (query.cursor !== undefined) {
    queryParameters.cursor = requireOpaqueQuery(query.cursor, 512);
  }
  const limit = query.limit ?? 20;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    throw new FlowWeftBackendClientError("INVALID_REQUEST");
  }
  queryParameters.limit = String(limit);

  let rawResponse: unknown;
  try {
    rawResponse = await requestPinnedJson({
      url: endpoint.toString(),
      method: "GET",
      headers: { Authorization: `Bearer ${session.accessToken}` },
      query: queryParameters,
      timeoutMillis: 10_000,
      allowPrivateNetwork: profile.api?.allowPrivateNetwork ?? false,
    });
  } catch {
    throw new FlowWeftBackendClientError("UPSTREAM_UNAVAILABLE");
  }
  const response = approvalInboxEnvelopeSchema.safeParse(rawResponse);
  if (!response.success) {
    throw new FlowWeftBackendClientError("INVALID_RESPONSE");
  }
  return Object.freeze({
    items: Object.freeze(response.data.data.items.map((item) => Object.freeze({
      task: Object.freeze({ ...item.task }),
      document: Object.freeze({ ...item.document }),
      workflowType: item.workflowType,
      workflowState: item.workflowState,
      actionableByCurrentUser: true as const,
    }))),
    nextCursor: response.data.data.nextCursor,
    total: response.data.data.total,
  });
}

function requireSessionProfile(
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
): void {
  if (session.sourceProfileId !== profile.id ||
    session.sourceProfileBindingDigest !== sourceProfileBindingDigest(profile)) {
    throw new FlowWeftBackendClientError("UNAUTHENTICATED");
  }
}

function aggregateDoctorStatus(statuses: readonly ConsoleDoctorStatus[]): ConsoleDoctorStatus {
  if (statuses.includes("ERROR")) return "ERROR";
  if (statuses.includes("WARNING")) return "WARNING";
  if (statuses.includes("HEALTHY")) return "HEALTHY";
  return "SKIPPED";
}

function requireOpaqueQuery(value: string, maximumLength: number): string {
  if (value.length < 1 || value.length > maximumLength || /[\s\u0000-\u001f\u007f]/u.test(value)) {
    throw new FlowWeftBackendClientError("INVALID_REQUEST");
  }
  return value;
}
