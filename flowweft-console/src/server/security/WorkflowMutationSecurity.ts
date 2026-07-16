import "server-only";

import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import type {
  ConsoleWorkflowMutationFormProtection,
} from "@/contracts/bff";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { readStoredConsoleSession } from "@/server/auth/ConsoleSessionAccess";
import {
  WORKFLOW_MUTATION_FLASH_TTL_SECONDS,
  workflowMutationFlashCookieName,
} from "@/server/security/WorkflowMutationFlashCookie";

const FLASH_WIRE_VERSION = "v1";
const MAXIMUM_FLASH_WIRE_BYTES = 3_800;
const FLASH_PAYLOAD_KEYS = [
  "expiresAtEpochMillis",
  "idempotencyKeyDigest",
  "instanceId",
  "issuedAtEpochMillis",
  "nonce",
  "operation",
  "outcome",
  "sessionIdDigest",
  "sourceProfileBindingDigest",
  "taskId",
] as const;

export type WorkflowMutationFlashOperation =
  | "CLAIM"
  | "DECIDE_APPROVE"
  | "DECIDE_REJECT"
  | "DECIDE_REQUEST_CHANGES"
  | "CREATE_COMMENT";

export type WorkflowMutationFlashOutcome = "succeeded" | "rejected" | "unknown";

export interface WorkflowMutationFlashProjection {
  readonly operation: WorkflowMutationFlashOperation;
  readonly taskId: string;
  readonly instanceId: string;
  readonly outcome: WorkflowMutationFlashOutcome;
}

interface WorkflowMutationFlashPayload extends WorkflowMutationFlashProjection {
  readonly sessionIdDigest: string;
  readonly sourceProfileBindingDigest: string;
  readonly idempotencyKeyDigest: string;
  readonly issuedAtEpochMillis: number;
  readonly expiresAtEpochMillis: number;
  readonly nonce: string;
}

export async function readWorkflowMutationFormProtection(): Promise<
  ConsoleWorkflowMutationFormProtection | null
> {
  const runtime = getConsoleAuthRuntime();
  const sessionId = (await cookies()).get(runtime.config.sessionCookieName)?.value;
  const session = await readStoredConsoleSession(sessionId);
  if (!sessionId || !session) return null;
  return Object.freeze({
    csrfToken: workflowMutationCsrfToken(sessionId, session),
    claimIdempotencyKey: idempotencyKey("claim"),
    decisionIdempotencyKeys: Object.freeze({
      APPROVE: idempotencyKey("approve"),
      REJECT: idempotencyKey("reject"),
      REQUEST_CHANGES: idempotencyKey("request-changes"),
    }),
    commentIdempotencyKey: idempotencyKey("comment"),
  });
}

/** Reads an authenticated flash. The approvals proxy clears its HttpOnly cookie on this response. */
export async function readWorkflowMutationFlash(): Promise<WorkflowMutationFlashProjection | null> {
  const runtime = getConsoleAuthRuntime();
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(runtime.config.sessionCookieName)?.value;
  const wire = cookieStore.get(workflowMutationFlashCookieName(runtime.config.sessionCookieName))?.value;
  if (!sessionId || !wire) return null;
  const session = await readStoredConsoleSession(sessionId);
  if (!session) return null;
  return verifyWorkflowMutationFlashWire(session, wire, Date.now());
}

export function createWorkflowMutationFlashWire(
  session: StoredConsoleSession,
  input: Readonly<{
    operation: WorkflowMutationFlashOperation;
    taskId: string;
    instanceId: string;
    idempotencyKey: string;
    outcome: WorkflowMutationFlashOutcome;
  }>,
  nowEpochMillis = Date.now(),
): string {
  requireFlashSession(session);
  requireTime(nowEpochMillis);
  const payload: WorkflowMutationFlashPayload = {
    sessionIdDigest: session.sessionIdDigest,
    sourceProfileBindingDigest: session.sourceProfileBindingDigest,
    operation: requireFlashOperation(input.operation),
    taskId: requireFlashTarget(input.taskId),
    instanceId: requireFlashTarget(input.instanceId),
    idempotencyKeyDigest: digestIdempotencyKey(input.idempotencyKey),
    outcome: requireFlashOutcome(input.outcome),
    issuedAtEpochMillis: nowEpochMillis,
    expiresAtEpochMillis: nowEpochMillis + WORKFLOW_MUTATION_FLASH_TTL_SECONDS * 1_000,
    nonce: randomBytes(16).toString("base64url"),
  };
  const encodedPayload = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  const signature = signFlashPayload(session, encodedPayload).toString("base64url");
  const wire = `${FLASH_WIRE_VERSION}.${encodedPayload}.${signature}`;
  if (Buffer.byteLength(wire, "utf8") > MAXIMUM_FLASH_WIRE_BYTES) {
    throw new Error("Workflow mutation flash is too large.");
  }
  return wire;
}

export function verifyWorkflowMutationFlashWire(
  session: StoredConsoleSession,
  wire: string,
  nowEpochMillis = Date.now(),
): WorkflowMutationFlashProjection | null {
  try {
    requireFlashSession(session);
    requireTime(nowEpochMillis);
    if (Buffer.byteLength(wire, "utf8") > MAXIMUM_FLASH_WIRE_BYTES) return null;
    const parts = wire.split(".");
    if (parts.length !== 3 || parts[0] !== FLASH_WIRE_VERSION) return null;
    const encodedPayload = parts[1] ?? "";
    const suppliedSignature = decodeCanonicalBase64Url(parts[2] ?? "", 32);
    const expectedSignature = signFlashPayload(session, encodedPayload);
    if (!timingSafeEqual(suppliedSignature, expectedSignature)) return null;
    const payloadBytes = decodeCanonicalBase64Url(encodedPayload, null);
    const decoded = new TextDecoder("utf-8", { fatal: true }).decode(payloadBytes);
    const parsed = JSON.parse(decoded) as unknown;
    if (!isPlainObject(parsed) || !hasExactKeys(parsed, FLASH_PAYLOAD_KEYS)) return null;
    const payload = parsed as unknown as WorkflowMutationFlashPayload;
    requireFlashOperation(payload.operation);
    requireFlashTarget(payload.taskId);
    requireFlashTarget(payload.instanceId);
    requireFlashOutcome(payload.outcome);
    requireDigest(payload.sessionIdDigest);
    requireDigest(payload.sourceProfileBindingDigest);
    requireDigest(payload.idempotencyKeyDigest);
    requireTime(payload.issuedAtEpochMillis);
    requireTime(payload.expiresAtEpochMillis);
    if (!/^[A-Za-z0-9_-]{22}$/u.test(payload.nonce) ||
      payload.sessionIdDigest !== session.sessionIdDigest ||
      payload.sourceProfileBindingDigest !== session.sourceProfileBindingDigest ||
      payload.expiresAtEpochMillis - payload.issuedAtEpochMillis !==
        WORKFLOW_MUTATION_FLASH_TTL_SECONDS * 1_000 ||
      nowEpochMillis < payload.issuedAtEpochMillis || nowEpochMillis >= payload.expiresAtEpochMillis) {
      return null;
    }
    return Object.freeze({
      operation: payload.operation,
      taskId: payload.taskId,
      instanceId: payload.instanceId,
      outcome: payload.outcome,
    });
  } catch {
    return null;
  }
}

export function verifyWorkflowMutationCsrfToken(
  sessionId: string,
  session: StoredConsoleSession,
  candidate: string,
): boolean {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(candidate)) return false;
  const expected = workflowMutationCsrfToken(sessionId, session);
  return timingSafeEqual(Buffer.from(candidate, "ascii"), Buffer.from(expected, "ascii"));
}

export function workflowMutationCsrfToken(
  sessionId: string,
  session: StoredConsoleSession,
): string {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(sessionId) ||
    !/^[A-Za-z0-9_-]{43}$/u.test(session.sessionIdDigest) ||
    !/^[A-Za-z0-9_-]{43}$/u.test(session.consoleOriginBindingDigest) ||
    !/^[A-Za-z0-9_-]{43}$/u.test(session.sourceProfileBindingDigest)) {
    throw new Error("Workflow mutation session binding is invalid.");
  }
  return createHash("sha256")
    .update("flowweft-console-workflow-csrf-v1\u0000", "utf8")
    .update(sessionId, "ascii")
    .update("\u0000", "utf8")
    .update(session.sessionIdDigest, "ascii")
    .update("\u0000", "utf8")
    .update(session.consoleOriginBindingDigest, "ascii")
    .update("\u0000", "utf8")
    .update(session.sourceProfileBindingDigest, "ascii")
    .digest("base64url");
}

function idempotencyKey(operation: string): string {
  return `console-${operation}-${randomBytes(24).toString("base64url")}`;
}

function signFlashPayload(session: StoredConsoleSession, encodedPayload: string): Buffer {
  const derivedKey = createHmac("sha256", Buffer.from(session.accessToken, "utf8"))
    .update("flowweft-console-workflow-flash-key-v1\u0000", "utf8")
    .update(session.sessionIdDigest, "ascii")
    .update("\u0000", "utf8")
    .update(session.consoleOriginBindingDigest, "ascii")
    .update("\u0000", "utf8")
    .update(session.sourceProfileBindingDigest, "ascii")
    .digest();
  try {
    return createHmac("sha256", derivedKey)
      .update(FLASH_WIRE_VERSION, "ascii")
      .update("\u0000", "utf8")
      .update(encodedPayload, "ascii")
      .digest();
  } finally {
    derivedKey.fill(0);
  }
}

function digestIdempotencyKey(value: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}$/u.test(value)) {
    throw new Error("Workflow mutation idempotency key is invalid.");
  }
  return createHash("sha256")
    .update("flowweft-console-workflow-idempotency-v1\u0000", "utf8")
    .update(value, "ascii")
    .digest("base64url");
}

function requireFlashSession(session: StoredConsoleSession): void {
  requireDigest(session.sessionIdDigest);
  requireDigest(session.consoleOriginBindingDigest);
  requireDigest(session.sourceProfileBindingDigest);
  if (session.accessToken.length < 1 || Buffer.byteLength(session.accessToken, "utf8") > 16_384 ||
    /[\u0000-\u0020\u007f]/u.test(session.accessToken)) {
    throw new Error("Workflow mutation flash session is invalid.");
  }
}

function requireFlashOperation(value: string): WorkflowMutationFlashOperation {
  if (value !== "CLAIM" && value !== "DECIDE_APPROVE" && value !== "DECIDE_REJECT" &&
    value !== "DECIDE_REQUEST_CHANGES" && value !== "CREATE_COMMENT") {
    throw new Error("Workflow mutation flash operation is invalid.");
  }
  return value;
}

function requireFlashOutcome(value: string): WorkflowMutationFlashOutcome {
  if (value !== "succeeded" && value !== "rejected" && value !== "unknown") {
    throw new Error("Workflow mutation flash outcome is invalid.");
  }
  return value;
}

function requireFlashTarget(value: string): string {
  if (Buffer.byteLength(value, "utf8") < 1 || Buffer.byteLength(value, "utf8") > 512 ||
    value !== value.trim() || /[\u0000-\u001f\u007f/\\?#]/u.test(value)) {
    throw new Error("Workflow mutation flash target is invalid.");
  }
  return value;
}

function requireDigest(value: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(value)) {
    throw new Error("Workflow mutation flash digest is invalid.");
  }
}

function requireTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error("Workflow mutation flash time is invalid.");
  }
}

function decodeCanonicalBase64Url(value: string, expectedBytes: number | null): Buffer {
  if (!/^[A-Za-z0-9_-]+$/u.test(value)) throw new Error("Workflow mutation flash encoding is invalid.");
  const decoded = Buffer.from(value, "base64url");
  if (decoded.length < 1 || decoded.toString("base64url") !== value ||
    expectedBytes !== null && decoded.length !== expectedBytes) {
    throw new Error("Workflow mutation flash encoding is invalid.");
  }
  return decoded;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  return actual.length === sortedExpected.length &&
    actual.every((key, index) => key === sortedExpected[index]);
}
