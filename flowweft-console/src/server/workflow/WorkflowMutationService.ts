import "server-only";

import { NextResponse, type NextRequest } from "next/server";
import type { StoredConsoleSession } from "@/server/auth/ConsoleAuthStore";
import { getConsoleAuthRuntime } from "@/server/auth/ConsoleAuthRuntime";
import { readStoredConsoleSession } from "@/server/auth/ConsoleSessionAccess";
import type { ConsoleServerConfig, ConsoleSourceProfileDefinition } from "@/server/config/schema";
import {
  claimWorkflowTask,
  createWorkflowTextComment,
  decideWorkflowTask,
  readWorkflowInstance,
  readWorkflowTask,
  WorkflowWebBackendClientError,
  type WorkflowTaskDecisionAction,
} from "@/server/dal/WorkflowWebBackendClient";
import { isLocale, type Locale } from "@/i18n/locale";
import {
  readBoundedForm,
  requireSameOriginMutation,
  requireSingleFormValue,
} from "@/server/security/RequestSecurity";
import {
  createWorkflowMutationFlashWire,
  verifyWorkflowMutationCsrfToken,
  type WorkflowMutationFlashOperation,
  type WorkflowMutationFlashOutcome,
} from "@/server/security/WorkflowMutationSecurity";
import {
  WORKFLOW_MUTATION_FLASH_TTL_SECONDS,
  workflowMutationFlashCookieName,
} from "@/server/security/WorkflowMutationFlashCookie";

export type ConsoleWorkflowMutation = "CLAIM" | "DECIDE" | "CREATE_COMMENT";

interface BoundWorkflowMutation {
  readonly operation: WorkflowMutationFlashOperation;
  readonly taskId: string;
  readonly instanceId: string;
  readonly idempotencyKey: string;
}

/**
 * Fixed-route BFF mutation boundary. Task visibility and allowedActions only gate presentation and
 * stale intent; the credential-bearing Workflow application route remains authoritative.
 */
export async function handleWorkflowMutation(
  request: NextRequest,
  operation: ConsoleWorkflowMutation,
): Promise<NextResponse> {
  const runtime = getConsoleAuthRuntime();
  let locale: Locale = runtime.config.defaultLocale;
  let upstreamWriteStarted = false;
  let trustedSession: StoredConsoleSession | null = null;
  let mutation: BoundWorkflowMutation | null = null;
  try {
    requireSameOriginMutation(request, runtime.config.publicOrigin);
    const form = await readBoundedForm(request, 24_576);
    requireExactFormShape(form, operation);
    const submittedLocale = requireSingleFormValue(form, "locale", 2);
    if (!isLocale(submittedLocale)) throw new WorkflowMutationRejectedError();
    locale = submittedLocale;

    const sessionId = request.cookies.get(runtime.config.sessionCookieName)?.value;
    const session = await readStoredConsoleSession(sessionId);
    if (!sessionId || !session || !verifyWorkflowMutationCsrfToken(
      sessionId,
      session,
      requireSingleFormValue(form, "csrfToken", 43),
    )) {
      throw new WorkflowMutationRejectedError();
    }
    trustedSession = session;
    const profile = runtime.sources.requireDefinition(session.sourceProfileId);
    const taskId = requireSingleFormValue(form, "taskId", 512);
    const expectedTaskVersion = parseExpectedVersion(
      requireSingleFormValue(form, "expectedTaskVersion", 16),
    );
    const idempotencyKey = requireSingleFormValue(form, "idempotencyKey", 128);
    const task = await readWorkflowTask(profile, session, taskId);
    let decisionAction: WorkflowTaskDecisionAction | null = null;
    let flashOperation: WorkflowMutationFlashOperation;
    if (operation === "CLAIM") {
      requireOperationIdempotencyKey(idempotencyKey, "claim");
      flashOperation = "CLAIM";
    } else if (operation === "DECIDE") {
      decisionAction = parseDecisionAction(requireSingleFormValue(form, "action", 96));
      requireOperationIdempotencyKey(idempotencyKey, decisionKeyOperation(decisionAction));
      flashOperation = decisionFlashOperation(decisionAction);
    } else {
      requireOperationIdempotencyKey(idempotencyKey, "comment");
      flashOperation = "CREATE_COMMENT";
    }
    mutation = Object.freeze({
      operation: flashOperation,
      taskId: task.task.id,
      instanceId: task.task.instanceId,
      idempotencyKey,
    });
    if (task.task.id !== taskId || task.task.recordVersion !== expectedTaskVersion) {
      throw new WorkflowMutationRejectedError();
    }

    if (operation === "CLAIM") {
      requireFreshAllowedAction(task.allowedActions, "CLAIM");
      upstreamWriteStarted = true;
      await claimWorkflowTask(profile, session, taskId, expectedTaskVersion, idempotencyKey);
    } else if (operation === "DECIDE") {
      const action = decisionAction!;
      requireFreshAllowedAction(task.allowedActions, action);
      upstreamWriteStarted = true;
      await decideWorkflowTask(profile, session, taskId, expectedTaskVersion, idempotencyKey, action);
    } else {
      await createComment(
        form,
        profile,
        session,
        task.subject,
        task.task.instanceId,
        task.allowedActions,
        idempotencyKey,
        () => { upstreamWriteStarted = true; },
      );
    }
    return mutationRedirect(runtime.config, locale, trustedSession, mutation, "succeeded");
  } catch (failure) {
    const outcome: WorkflowMutationFlashOutcome = upstreamWriteStarted &&
      !(failure instanceof WorkflowWebBackendClientError && failure.code === "MUTATION_REJECTED")
      ? "unknown"
      : "rejected";
    return mutationRedirect(
      runtime.config,
      locale,
      trustedSession,
      mutation,
      outcome,
    );
  }
}

async function createComment(
  form: URLSearchParams,
  profile: ConsoleSourceProfileDefinition,
  session: StoredConsoleSession,
  subject: Readonly<{ type: string; id: string; revision: string; digest: string }>,
  instanceId: string,
  allowedActions: readonly string[],
  idempotencyKey: string,
  markWriteStarted: () => void,
): Promise<void> {
  if (!allowedActions.includes("CREATE_COMMENT") && !allowedActions.includes("COMMENT")) {
    throw new WorkflowMutationRejectedError();
  }
  const expectedInstanceVersion = parseExpectedVersion(
    requireSingleFormValue(form, "expectedInstanceVersion", 16),
  );
  const commentText = requireCommentText(form);
  const instance = await readWorkflowInstance(profile, session, instanceId);
  if (instance.recordVersion !== expectedInstanceVersion || instance.id !== instanceId ||
    !sameSubject(instance.subject, subject)) {
    throw new WorkflowMutationRejectedError();
  }
  // The instance route came from the fresh task read; the browser cannot choose it directly.
  markWriteStarted();
  await createWorkflowTextComment(
    profile,
    session,
    instance.id,
    expectedInstanceVersion,
    idempotencyKey,
    commentText,
  );
}

function requireFreshAllowedAction(allowedActions: readonly string[], action: string): void {
  if (!allowedActions.includes(action)) throw new WorkflowMutationRejectedError();
}

function requireExactFormShape(form: URLSearchParams, operation: ConsoleWorkflowMutation): void {
  const names = ["locale", "taskId", "expectedTaskVersion", "csrfToken", "idempotencyKey"];
  if (operation === "DECIDE") names.push("action");
  if (operation === "CREATE_COMMENT") names.push("expectedInstanceVersion", "commentText");
  const submitted = Array.from(form.keys());
  if (submitted.length !== names.length || submitted.some((name) => !names.includes(name)) ||
    new Set(submitted).size !== submitted.length) {
    throw new WorkflowMutationRejectedError();
  }
}

function requireCommentText(form: URLSearchParams): string {
  const values = form.getAll("commentText");
  const value = values.length === 1 ? values[0] : undefined;
  if (value === undefined || value.trim().length === 0 || Buffer.byteLength(value, "utf8") > 8_192 ||
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value) || containsUnsafeUnicode(value)) {
    throw new WorkflowMutationRejectedError();
  }
  return value;
}

function requireOperationIdempotencyKey(value: string, operation: string): void {
  if (!/^[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}$/u.test(value) ||
    !value.startsWith(`console-${operation}-`)) {
    throw new WorkflowMutationRejectedError();
  }
}

function decisionKeyOperation(action: WorkflowTaskDecisionAction): string {
  if (action === "APPROVE") return "approve";
  if (action === "REJECT") return "reject";
  return "request-changes";
}

function decisionFlashOperation(action: WorkflowTaskDecisionAction): WorkflowMutationFlashOperation {
  if (action === "APPROVE") return "DECIDE_APPROVE";
  if (action === "REJECT") return "DECIDE_REJECT";
  return "DECIDE_REQUEST_CHANGES";
}

function parseDecisionAction(value: string): WorkflowTaskDecisionAction {
  if (value !== "APPROVE" && value !== "REJECT" && value !== "REQUEST_CHANGES") {
    throw new WorkflowMutationRejectedError();
  }
  return value;
}

function parseExpectedVersion(value: string): number {
  if (!/^(?:0|[1-9][0-9]{0,15})$/u.test(value)) throw new WorkflowMutationRejectedError();
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw new WorkflowMutationRejectedError();
  return parsed;
}

function sameSubject(
  left: Readonly<{ type: string; id: string; revision: string; digest: string }>,
  right: Readonly<{ type: string; id: string; revision: string; digest: string }>,
): boolean {
  return left.type === right.type && left.id === right.id && left.revision === right.revision &&
    left.digest === right.digest;
}

function containsUnsafeUnicode(value: string): boolean {
  for (let offset = 0; offset < value.length;) {
    const codePoint = value.codePointAt(offset);
    if (codePoint === undefined || codePoint >= 0xd800 && codePoint <= 0xdfff ||
      codePoint >= 0xfdd0 && codePoint <= 0xfdef ||
      (codePoint & 0xffff) === 0xfffe || (codePoint & 0xffff) === 0xffff ||
      /\p{Cf}/u.test(String.fromCodePoint(codePoint))) {
      return true;
    }
    offset += codePoint > 0xffff ? 2 : 1;
  }
  return false;
}

function mutationRedirect(
  config: ConsoleServerConfig,
  locale: Locale,
  session: StoredConsoleSession | null,
  mutation: BoundWorkflowMutation | null,
  outcome: WorkflowMutationFlashOutcome,
): NextResponse {
  if (!config.publicOrigin) {
    return protectedProblem(503);
  }
  const target = new URL(`/${locale}/approvals`, config.publicOrigin);
  if (mutation) target.searchParams.set("taskId", mutation.taskId);
  const response = NextResponse.redirect(target, 303);
  const flashCookieName = workflowMutationFlashCookieName(config.sessionCookieName);
  if (session && mutation) {
    try {
      response.cookies.set(flashCookieName, createWorkflowMutationFlashWire(session, {
        operation: mutation.operation,
        taskId: mutation.taskId,
        instanceId: mutation.instanceId,
        idempotencyKey: mutation.idempotencyKey,
        outcome,
      }), {
        httpOnly: true,
        secure: config.secureCookies,
        sameSite: "strict",
        path: "/",
        maxAge: WORKFLOW_MUTATION_FLASH_TTL_SECONDS,
      });
    } catch {
      clearMutationFlashCookie(response, config, flashCookieName);
    }
  } else {
    clearMutationFlashCookie(response, config, flashCookieName);
  }
  applyProtectedHeaders(response);
  return response;
}

function clearMutationFlashCookie(
  response: NextResponse,
  config: ConsoleServerConfig,
  name: string,
): void {
  response.cookies.set(name, "", {
    httpOnly: true,
    secure: config.secureCookies,
    sameSite: "strict",
    path: "/",
    maxAge: 0,
  });
}

function protectedProblem(status: number): NextResponse {
  const response = NextResponse.json(
    { code: "UNAVAILABLE", message: "Workflow operation is unavailable." },
    { status },
  );
  applyProtectedHeaders(response);
  return response;
}

function applyProtectedHeaders(response: NextResponse): void {
  response.headers.set("Cache-Control", "no-store, max-age=0");
  response.headers.set("Pragma", "no-cache");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("X-Content-Type-Options", "nosniff");
}

class WorkflowMutationRejectedError extends Error {
  constructor() {
    super("Workflow mutation rejected.");
    this.name = "WorkflowMutationRejectedError";
  }
}
