import { notFound } from "next/navigation";
import type {
  ConsoleWorkflowCommentPage,
  ConsoleWorkflowDefinitionDetail,
  ConsoleWorkflowDefinitionPage,
  ConsoleWorkflowHistoryPage,
  ConsoleWorkflowInstance,
  ConsoleWorkflowMutationFormProtection,
  ConsoleWorkflowMutationResult,
  ConsoleWorkflowTaskDetail,
  ConsoleWorkflowTaskFormSummary,
  ConsoleWorkflowTaskPage,
} from "@/contracts/bff";
import {
  WorkflowWorkbench,
  WorkflowWorkbenchUnavailable,
} from "@/features/workflow/WorkflowWorkbench";
import { isLocale } from "@/i18n/locale";
import { getConsoleDataAccess } from "@/server/dal/ConsoleDataAccess";
import {
  readWorkflowMutationFlash,
  readWorkflowMutationFormProtection,
} from "@/server/security/WorkflowMutationSecurity";

interface WorkflowPageProps {
  readonly params: Promise<{ locale: string }>;
  readonly searchParams: Promise<{
    taskId?: string | string[];
    definitionId?: string | string[];
    taskCursor?: string | string[];
    definitionCursor?: string | string[];
    historyCursor?: string | string[];
    commentCursor?: string | string[];
  }>;
}

export default async function WorkflowPage({ params, searchParams }: WorkflowPageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const raw = await searchParams;
  if (Object.values(raw).some(Array.isArray)) return <WorkflowWorkbenchUnavailable locale={locale} />;

  const mutationFlash = await readWorkflowMutationFlash().catch(() => null);
  const taskId = mutationFlash?.taskId ?? raw.taskId as string | undefined;
  const definitionId = raw.definitionId as string | undefined;
  const cursors = {
    task: raw.taskCursor as string | undefined,
    definition: raw.definitionCursor as string | undefined,
    history: raw.historyCursor as string | undefined,
    comment: raw.commentCursor as string | undefined,
  };
  const dataAccess = getConsoleDataAccess();
  const [tasksResult, definitionsResult] = await Promise.allSettled([
    dataAccess.getWorkflowTaskPage({ ...(cursors.task ? { cursor: cursors.task } : {}), limit: 24 }),
    dataAccess.getWorkflowDefinitionPage({
      ...(cursors.definition ? { cursor: cursors.definition } : {}), limit: 24,
    }),
  ] as const);
  const tasks: ConsoleWorkflowTaskPage | null = tasksResult.status === "fulfilled" ? tasksResult.value : null;
  const definitions: ConsoleWorkflowDefinitionPage | null = definitionsResult.status === "fulfilled"
    ? definitionsResult.value
    : null;

  let taskDetail: ConsoleWorkflowTaskDetail | null = null;
  let instance: ConsoleWorkflowInstance | null = null;
  let history: ConsoleWorkflowHistoryPage | null = null;
  let comments: ConsoleWorkflowCommentPage | null = null;
  let form: ConsoleWorkflowTaskFormSummary | null = null;
  let selectedTaskUnavailable = Boolean((cursors.history || cursors.comment) && !taskId);
  let historyUnavailable = false;
  let commentsUnavailable = false;
  let formUnavailable = false;
  let freshTaskReadAttempted = false;

  if (taskId) {
    freshTaskReadAttempted = true;
    try {
      taskDetail = await dataAccess.getWorkflowTask(taskId);
      instance = await dataAccess.getWorkflowInstance(taskDetail.task.instanceId);
      if (instance.id !== taskDetail.task.instanceId || !sameSubject(instance.subject, taskDetail.subject)) {
        throw new Error("Workflow task and instance ownership are inconsistent.");
      }
      const [historyResult, commentsResult, formResult] = await Promise.allSettled([
        dataAccess.getWorkflowHistoryPage(instance.id, {
          ...(cursors.history ? { cursor: cursors.history } : {}), limit: 50,
        }),
        dataAccess.getWorkflowCommentPage(instance.id, {
          ...(cursors.comment ? { cursor: cursors.comment } : {}), limit: 30,
        }),
        taskDetail.formId === null ? Promise.resolve(null) : dataAccess.getWorkflowTaskForm(taskDetail.task.id),
      ] as const);
      if (historyResult.status === "fulfilled") history = historyResult.value;
      else historyUnavailable = true;
      if (commentsResult.status === "fulfilled") comments = commentsResult.value;
      else commentsUnavailable = true;
      if (formResult.status === "fulfilled") {
        form = formResult.value;
        if (form && (form.formId !== taskDetail.formId || form.version !== taskDetail.formVersion)) {
          form = null;
          formUnavailable = true;
        }
      } else formUnavailable = true;
    } catch {
      taskDetail = null;
      instance = null;
      history = null;
      comments = null;
      form = null;
      selectedTaskUnavailable = true;
      historyUnavailable = false;
      commentsUnavailable = false;
      formUnavailable = false;
    }
  }

  let definitionDetail: ConsoleWorkflowDefinitionDetail | null = null;
  let selectedDefinitionUnavailable = false;
  if (definitionId && definitions) {
    try {
      definitionDetail = await dataAccess.getWorkflowDefinition(definitionId);
    } catch {
      selectedDefinitionUnavailable = true;
    }
  } else if (definitionId) selectedDefinitionUnavailable = true;

  const safeSelectedTaskId = selectedTaskUnavailable ? null : taskId ?? null;
  const safeSelectedDefinitionId = selectedDefinitionUnavailable ? null : definitionId ?? null;
  const safeCursors = {
    ...(tasks && cursors.task ? { task: cursors.task } : {}),
    ...(definitions && cursors.definition ? { definition: cursors.definition } : {}),
    ...(safeSelectedTaskId && history && cursors.history ? { history: cursors.history } : {}),
    ...(safeSelectedTaskId && comments && cursors.comment ? { comment: cursors.comment } : {}),
  };
  const flashMatchesFreshTarget = Boolean(mutationFlash && freshTaskReadAttempted &&
    taskId === mutationFlash.taskId && (taskDetail === null || instance !== null &&
      taskDetail.task.id === mutationFlash.taskId &&
      taskDetail.task.instanceId === mutationFlash.instanceId && instance.id === mutationFlash.instanceId));
  const mutationResult: ConsoleWorkflowMutationResult | undefined = flashMatchesFreshTarget
    ? mutationFlash!.outcome
    : undefined;
  if (!tasks && !definitions && !mutationResult) {
    return <WorkflowWorkbenchUnavailable locale={locale} />;
  }
  let mutationProtection: ConsoleWorkflowMutationFormProtection | null = null;
  if (taskDetail && instance && safeSelectedTaskId) {
    try {
      mutationProtection = await readWorkflowMutationFormProtection();
    } catch {
      mutationProtection = null;
    }
  }

  return <WorkflowWorkbench
    locale={locale}
    tasks={tasks}
    definitions={definitions}
    selectedTaskId={safeSelectedTaskId}
    selectedDefinitionId={safeSelectedDefinitionId}
    taskDetail={taskDetail}
    instance={instance}
    history={history}
    comments={comments}
    form={form}
    definitionDetail={definitionDetail}
    taskCapabilityUnavailable={tasks === null}
    definitionCapabilityUnavailable={definitions === null}
    selectedTaskUnavailable={selectedTaskUnavailable}
    selectedDefinitionUnavailable={selectedDefinitionUnavailable}
    historyUnavailable={historyUnavailable}
    commentsUnavailable={commentsUnavailable}
    formUnavailable={formUnavailable}
    cursors={safeCursors}
    mutationProtection={mutationProtection}
    mutationResult={mutationResult}
  />;
}

function sameSubject(
  left: ConsoleWorkflowTaskDetail["subject"],
  right: ConsoleWorkflowTaskDetail["subject"],
): boolean {
  return left.type === right.type && left.id === right.id && left.revision === right.revision &&
    left.digest === right.digest;
}
