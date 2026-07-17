import Link from "next/link";
import type { Route } from "next";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
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
import type { Locale } from "@/i18n/locale";

export interface WorkflowWorkbenchCursors {
  readonly task?: string;
  readonly definition?: string;
  readonly history?: string;
  readonly comment?: string;
}

export interface WorkflowWorkbenchProps {
  readonly locale: Locale;
  readonly tasks: ConsoleWorkflowTaskPage | null;
  readonly definitions: ConsoleWorkflowDefinitionPage | null;
  readonly selectedTaskId: string | null;
  readonly selectedDefinitionId: string | null;
  readonly taskDetail: ConsoleWorkflowTaskDetail | null;
  readonly instance: ConsoleWorkflowInstance | null;
  readonly history: ConsoleWorkflowHistoryPage | null;
  readonly comments: ConsoleWorkflowCommentPage | null;
  readonly form: ConsoleWorkflowTaskFormSummary | null;
  readonly definitionDetail: ConsoleWorkflowDefinitionDetail | null;
  readonly taskCapabilityUnavailable?: boolean;
  readonly definitionCapabilityUnavailable?: boolean;
  readonly selectedTaskUnavailable?: boolean;
  readonly selectedDefinitionUnavailable?: boolean;
  readonly historyUnavailable?: boolean;
  readonly commentsUnavailable?: boolean;
  readonly formUnavailable?: boolean;
  readonly cursors?: WorkflowWorkbenchCursors;
  readonly mutationProtection?: ConsoleWorkflowMutationFormProtection | null;
  readonly mutationResult?: ConsoleWorkflowMutationResult;
}

const copy = {
  zh: {
    eyebrow: "02 / 通用流程控制室",
    title: "Workflow 流程工作台",
    summary: "待办、实例、历史、评论与定义都来自当前宿主身份的实时授权投影。页面没有 tenant、token、endpoint 或 Provider secret，也不会把可见按钮当成执行权限。",
    live: "LIVE / CONTROLLED WRITES",
    visibleTasks: "本页待办",
    visibleDefinitions: "本页定义",
    taskReel: "当前身份待办",
    taskBoundary: "服务端决定当前用户是否可领取、办理或仅可查看；浏览器不能提交办理人或 tenant 过滤器。",
    emptyTasks: "当前授权范围内没有可见待办。",
    taskUnavailable: "任务读取能力未安装或暂时不可用。不会用历史审批接口或模拟任务代替。",
    nextTasks: "下一页待办",
    actionable: "当前可办理",
    readOnly: "只读可见",
    claimed: "已由我领取",
    unclaimed: "尚未领取",
    due: "截止",
    updated: "更新",
    selectTask: "选择一条待办，展开实例航线、表单边界与协作证据。",
    hiddenTask: "所选待办、关联实例或当前授权不可用；不存在与无权限保持不可区分。",
    taskDossier: "任务与实例案卷",
    instance: "流程实例",
    subject: "业务对象",
    allowedActions: "服务端声明动作",
    noActions: "无可执行动作",
    mutationBoundary: "领取、审批与纯文本评论已接入固定同源 BFF；每次操作都重验会话、CSRF、If-Match 与幂等键。转办、加签、退回和表单提交仍保持关闭。可见按钮不是授权证明。",
    mutationUnavailable: "当前安全表单材料不可用；所有写操作保持关闭。",
    claim: "领取任务",
    approve: "通过",
    reject: "驳回",
    requestChanges: "要求修改",
    commentLabel: "添加纯文本评论",
    commentPlaceholder: "输入评论；@ 提及将在安全主体选择器接入后开放。",
    createComment: "发布评论",
    mutationSucceeded: "操作已收到严格回执，页面数据已重新读取。",
    mutationRejected: "操作未执行：会话、任务版本或当前权限已变化。请重新选择任务后再试。",
    mutationUnknown: "操作结果未确认。请先核对任务状态与历史记录，不要直接重复提交。",
    state: "状态",
    version: "版本",
    definition: "流程定义",
    created: "创建",
    form: "任务表单",
    noForm: "此任务没有关联表单。",
    formUnavailable: "表单能力不可用；页面不会回显原始响应或猜测字段。",
    formBoundary: "仅展示 schema 身份与摘要；schema 文档、UI 文档和投影数据仍留在服务端，等待受控渲染器。",
    dialect: "Schema 方言",
    projectedData: "授权投影数据",
    present: "存在（未下发）",
    absent: "无",
    timeline: "实例时间线",
    historyUnavailable: "历史能力不可用；空时间线不会被伪装成真实结果。",
    emptyHistory: "当前实例没有可见历史事件。",
    nextHistory: "后续历史",
    currentUser: "当前用户执行",
    systemOrOther: "系统 / 其他参与者",
    sequence: "序列",
    reason: "原因",
    comments: "安全评论流",
    commentsBoundary: "@ 提及只展示授权时固化的名称，不把 principal id 送入浏览器。评论正文按纯文本呈现。",
    commentsUnavailable: "评论能力不可用；不会把上游错误当作空评论。",
    emptyComments: "当前实例没有可见评论。",
    nextComments: "后续评论",
    mine: "我的评论",
    participant: "参与者",
    definitions: "版本化定义账本",
    definitionBoundary: "定义源码与可执行结构不会进入浏览器；这里只保留版本、codec、摘要和安全诊断。",
    emptyDefinitions: "当前授权范围内没有可见定义。",
    definitionUnavailable: "定义读取能力未安装或暂时不可用。",
    nextDefinitions: "下一页定义",
    selectDefinition: "选择定义查看 codec 与发布诊断。",
    hiddenDefinition: "所选定义不可见或暂时不可用；页面不会回显请求标识。",
    codec: "Codec",
    recordVersion: "记录版本",
    diagnostics: "发布诊断",
    noDiagnostics: "没有可见诊断。",
    contentDigest: "内容摘要",
    sourceDigest: "源码摘要",
    unavailableEyebrow: "02 / FAIL-CLOSED WORKFLOW SURFACE",
    unavailableTitle: "Workflow 能力当前不可用",
    unavailableDetail: "服务端没有返回可验证的 Workflow Web 契约，或当前会话没有任何可读流程能力。页面不会降级为浏览器直连、旧审批接口或样例数据。",
    unavailableBoundary: "请检查 Workflow Web Starter、应用端口与当前用户授权；浏览器不会收到内部异常、tenant、endpoint 或 secret。",
  },
  en: {
    eyebrow: "02 / GENERIC FLOW CONTROL ROOM",
    title: "Workflow operations workbench",
    summary: "Tasks, instances, history, comments and definitions are live authorization projections for the current host identity. No tenant, token, endpoint or provider secret enters this page, and visible controls never imply execution authority.",
    live: "LIVE / CONTROLLED WRITES",
    visibleTasks: "Tasks here",
    visibleDefinitions: "Definitions here",
    taskReel: "Current-principal tasks",
    taskBoundary: "The server decides whether the current user may claim, act or only read. The browser cannot submit assignee or tenant filters.",
    emptyTasks: "No tasks are visible in the current authorization scope.",
    taskUnavailable: "Task reading is not installed or is temporarily unavailable. Legacy approval routes and simulated tasks are never substituted.",
    nextTasks: "Next tasks",
    actionable: "Actionable now",
    readOnly: "Read-only visibility",
    claimed: "Claimed by me",
    unclaimed: "Unclaimed",
    due: "Due",
    updated: "Updated",
    selectTask: "Select a task to unfold its instance route, form boundary and collaboration evidence.",
    hiddenTask: "The selected task, related instance or current authority is unavailable. Absence and authorization remain indistinguishable.",
    taskDossier: "Task and instance dossier",
    instance: "Workflow instance",
    subject: "Business subject",
    allowedActions: "Server-declared actions",
    noActions: "No actions available",
    mutationBoundary: "Claim, decisions and text comments use fixed same-origin BFF routes with fresh session, CSRF, If-Match and idempotency checks. Delegation, add-sign, return and form submission remain locked. A visible control is never authorization proof.",
    mutationUnavailable: "Secure form material is unavailable; every write remains locked.",
    claim: "Claim task",
    approve: "Approve",
    reject: "Reject",
    requestChanges: "Request changes",
    commentLabel: "Add a text comment",
    commentPlaceholder: "Enter a comment. Mentions remain locked until the safe principal picker is connected.",
    createComment: "Post comment",
    mutationSucceeded: "The operation returned a strict receipt and page data was read again.",
    mutationRejected: "The operation did not run because the session, task version or current authority changed. Select the task again before retrying.",
    mutationUnknown: "The outcome is not confirmed. Inspect task state and history before attempting anything again.",
    state: "State",
    version: "Version",
    definition: "Definition",
    created: "Created",
    form: "Task form",
    noForm: "This task has no associated form.",
    formUnavailable: "Form capability is unavailable. Raw responses and guessed fields are never rendered.",
    formBoundary: "Only schema identity and digests are shown. Schema documents, UI documents and projected values remain server-side until a controlled renderer exists.",
    dialect: "Schema dialect",
    projectedData: "Authorized projected data",
    present: "Present (not delivered)",
    absent: "None",
    timeline: "Instance timeline",
    historyUnavailable: "History capability is unavailable. An empty rail is never presented as real evidence.",
    emptyHistory: "No visible history events exist for this instance.",
    nextHistory: "Later history",
    currentUser: "Performed by current user",
    systemOrOther: "System / another participant",
    sequence: "Sequence",
    reason: "Reason",
    comments: "Safe comment stream",
    commentsBoundary: "Mentions expose only the authorization-time display snapshot, never a principal id. Comment text is rendered as plain text.",
    commentsUnavailable: "Comment capability is unavailable. Upstream failure is never disguised as an empty thread.",
    emptyComments: "No comments are visible for this instance.",
    nextComments: "Later comments",
    mine: "My comment",
    participant: "Participant",
    definitions: "Versioned definition ledger",
    definitionBoundary: "Definition source and executable structure never enter the browser. Only versions, codec identity, digests and safe diagnostics cross the boundary.",
    emptyDefinitions: "No definitions are visible in the current authorization scope.",
    definitionUnavailable: "Definition reading is not installed or is temporarily unavailable.",
    nextDefinitions: "Next definitions",
    selectDefinition: "Select a definition to inspect codec identity and publication diagnostics.",
    hiddenDefinition: "The selected definition is hidden or temporarily unavailable. Its request identifier is not echoed.",
    codec: "Codec",
    recordVersion: "Record version",
    diagnostics: "Publication diagnostics",
    noDiagnostics: "No visible diagnostics.",
    contentDigest: "Content digest",
    sourceDigest: "Source digest",
    unavailableEyebrow: "02 / FAIL-CLOSED WORKFLOW SURFACE",
    unavailableTitle: "Workflow capability is unavailable",
    unavailableDetail: "The server returned no verifiable Workflow Web contract, or this session has no readable Workflow capability. The page never falls back to browser-direct access, legacy approval routes or sample data.",
    unavailableBoundary: "Check the Workflow Web starter, application ports and current user authorization. Internal errors, tenant values, endpoints and secrets never reach the browser.",
  },
} as const;

export function WorkflowWorkbench(props: WorkflowWorkbenchProps) {
  const text = copy[props.locale];
  const safeProps: WorkflowWorkbenchProps = {
    ...props,
    selectedTaskId: props.selectedTaskUnavailable ? null : props.selectedTaskId,
    selectedDefinitionId: props.selectedDefinitionUnavailable ? null : props.selectedDefinitionId,
    mutationProtection: props.selectedTaskUnavailable ? null : props.mutationProtection,
  };
  return (
    <article className="workflow-workbench">
      <header className="workflow-workbench__hero">
        <div>
          <p className="eyebrow">{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <p>{text.summary}</p>
        </div>
        <div className="workflow-workbench__signal" aria-label={text.live}>
          <i aria-hidden="true" />
          <span>{text.live}</span>
          <dl>
            <div><dt>{text.visibleTasks}</dt><dd>{safeProps.tasks?.items.length ?? "—"}</dd></div>
            <div><dt>{text.visibleDefinitions}</dt><dd>{safeProps.definitions?.items.length ?? "—"}</dd></div>
          </dl>
        </div>
      </header>

      {safeProps.mutationResult ? <MutationResultBanner
        locale={safeProps.locale}
        result={safeProps.mutationResult}
      /> : null}

      <div className="workflow-workbench__grid">
        <TaskReel {...safeProps} />
        <TaskDossier {...safeProps} />
      </div>
      <DefinitionLedger {...safeProps} />
    </article>
  );
}

export function WorkflowWorkbenchUnavailable({ locale }: { readonly locale: Locale }) {
  const text = copy[locale];
  return (
    <article className="workflow-workbench workflow-workbench--unavailable">
      <header className="workflow-workbench__hero">
        <div>
          <p className="eyebrow">{text.unavailableEyebrow}</p>
          <h1>{text.unavailableTitle}</h1>
          <p>{text.unavailableDetail}</p>
        </div>
        <div className="workflow-workbench__signal"><i aria-hidden="true" /><StatusBadge tone="error">UNAVAILABLE</StatusBadge></div>
      </header>
      <section className="workflow-workbench__closed-boundary">
        <span aria-hidden="true">×</span><p>{text.unavailableBoundary}</p>
      </section>
    </article>
  );
}

function TaskReel(props: WorkflowWorkbenchProps) {
  const text = copy[props.locale];
  const cursors = props.cursors ?? {};
  return (
    <section className="workflow-task-reel" aria-labelledby="workflow-task-reel-title">
      <header><div><span>01</span><h2 id="workflow-task-reel-title">{text.taskReel}</h2></div><p>{text.taskBoundary}</p></header>
      {props.taskCapabilityUnavailable ? <ClosedPanel message={text.taskUnavailable} /> : props.tasks?.items.length === 0 ? (
        <p className="workflow-workbench__empty">{text.emptyTasks}</p>
      ) : (
        <ol>
          {props.tasks?.items.map((task, index) => (
            <li key={task.id} data-selected={task.id === props.selectedTaskId}>
              <Link href={workflowHref(props.locale, {
                taskId: task.id,
                ...(cursors.task ? { taskCursor: cursors.task } : {}),
                ...(props.selectedDefinitionId ? { definitionId: props.selectedDefinitionId } : {}),
                ...(cursors.definition ? { definitionCursor: cursors.definition } : {}),
              })} aria-current={task.id === props.selectedTaskId ? "page" : undefined}>
                <span className="workflow-task-reel__index">T{String(index + 1).padStart(2, "0")}</span>
                <div className="workflow-task-reel__identity"><strong>{task.name}</strong><code>{task.id}</code></div>
                <div className="workflow-task-reel__status">
                  <StatusBadge tone={task.actionableByCurrentUser ? "ready" : stateTone(task.state)}>{task.state}</StatusBadge>
                  <small>{task.actionableByCurrentUser ? text.actionable : text.readOnly} · {task.claimantIsCurrentUser ? text.claimed : text.unclaimed}</small>
                </div>
                <dl>
                  <div><dt>{text.updated}</dt><dd><time dateTime={isoTime(task.updatedAt)}>{formatTime(task.updatedAt, props.locale)}</time></dd></div>
                  <div><dt>{text.due}</dt><dd>{task.dueAt === null ? "—" : <time dateTime={isoTime(task.dueAt)}>{formatTime(task.dueAt, props.locale)}</time>}</dd></div>
                </dl>
              </Link>
            </li>
          ))}
        </ol>
      )}
      {props.tasks?.nextCursor ? <Link className="workflow-workbench__next" rel="next" href={workflowHref(props.locale, {
        taskCursor: props.tasks.nextCursor,
        ...(props.selectedTaskId ? { taskId: props.selectedTaskId } : {}),
        ...(props.selectedDefinitionId ? { definitionId: props.selectedDefinitionId } : {}),
        ...(props.selectedTaskId && cursors.history ? { historyCursor: cursors.history } : {}),
        ...(props.selectedTaskId && cursors.comment ? { commentCursor: cursors.comment } : {}),
        ...(cursors.definition ? { definitionCursor: cursors.definition } : {}),
      })}>{text.nextTasks}<span aria-hidden="true">→</span></Link> : null}
    </section>
  );
}

function TaskDossier(props: WorkflowWorkbenchProps) {
  const text = copy[props.locale];
  if (props.selectedTaskUnavailable) return <section className="workflow-task-dossier"><ClosedPanel message={text.hiddenTask} /></section>;
  if (!props.taskDetail || !props.instance) return (
    <section className="workflow-task-dossier workflow-task-dossier--empty"><span aria-hidden="true">↳</span><p>{text.selectTask}</p></section>
  );
  const task = props.taskDetail;
  const instance = props.instance;
  return (
    <section className="workflow-task-dossier" aria-labelledby="workflow-task-dossier-title">
      <header className="workflow-task-dossier__header">
        <div><p className="eyebrow">02 / {text.taskDossier}</p><h2 id="workflow-task-dossier-title">{task.task.name}</h2><code>{task.task.id}</code></div>
        <StatusBadge tone={stateTone(task.task.state)}>{task.task.state}</StatusBadge>
      </header>
      <div className="workflow-task-dossier__facts">
        <section>
          <h3>{text.instance}</h3>
          <dl>
            <div><dt>{text.state}</dt><dd>{instance.state}</dd></div>
            <div><dt>{text.version}</dt><dd>v{instance.recordVersion}</dd></div>
            <div><dt>{text.definition}</dt><dd>{instance.definitionId}<small>{instance.definitionVersion}</small></dd></div>
            <div><dt>{text.created}</dt><dd><time dateTime={isoTime(instance.createdAt)}>{formatTime(instance.createdAt, props.locale)}</time></dd></div>
          </dl>
        </section>
        <section>
          <h3>{text.subject}</h3>
          <dl>
            <div><dt>TYPE</dt><dd>{task.subject.type}</dd></div>
            <div><dt>ID</dt><dd>{task.subject.id}</dd></div>
            <div><dt>REV</dt><dd>{task.subject.revision}</dd></div>
            <div><dt>SHA-256</dt><dd title={task.subject.digest}>{shortDigest(task.subject.digest)}</dd></div>
          </dl>
        </section>
      </div>
      <section className="workflow-task-dossier__actions">
        <header><h3>{text.allowedActions}</h3><p>{text.mutationBoundary}</p></header>
        <div className="workflow-task-dossier__action-stack">
          <div className="workflow-task-dossier__action-codes">{task.allowedActions.length === 0 ? <span>{text.noActions}</span> : task.allowedActions.map((action) => <code key={action}>{action}</code>)}</div>
          <MutationControls
            locale={props.locale}
            task={task}
            instance={instance}
            protection={props.mutationProtection ?? null}
          />
        </div>
      </section>
      <FormBoundary locale={props.locale} task={task} form={props.form} unavailable={props.formUnavailable ?? false} />
      <div className="workflow-task-dossier__evidence">
        <HistoryRail {...props} />
        <CommentStream {...props} />
      </div>
    </section>
  );
}

function MutationResultBanner({ locale, result }: {
  readonly locale: Locale;
  readonly result: ConsoleWorkflowMutationResult;
}) {
  const text = copy[locale];
  const message = result === "succeeded"
    ? text.mutationSucceeded
    : result === "rejected"
      ? text.mutationRejected
      : text.mutationUnknown;
  return <aside
    className={`workflow-mutation-result workflow-mutation-result--${result}`}
    role={result === "succeeded" ? "status" : "alert"}
  ><span aria-hidden="true">{result === "succeeded" ? "✓" : "!"}</span><p>{message}</p></aside>;
}

function MutationControls({ locale, task, instance, protection }: {
  readonly locale: Locale;
  readonly task: ConsoleWorkflowTaskDetail;
  readonly instance: ConsoleWorkflowInstance;
  readonly protection: ConsoleWorkflowMutationFormProtection | null;
}) {
  const text = copy[locale];
  const decisions = (["APPROVE", "REJECT", "REQUEST_CHANGES"] as const)
    .filter((action) => task.allowedActions.includes(action));
  const canClaim = task.allowedActions.includes("CLAIM");
  const canComment = task.allowedActions.includes("CREATE_COMMENT") || task.allowedActions.includes("COMMENT");
  if (!canClaim && decisions.length === 0 && !canComment) return null;
  if (!protection) return <p className="workflow-mutation-controls__unavailable">{text.mutationUnavailable}</p>;
  return (
    <div className="workflow-mutation-controls">
      <div className="workflow-mutation-controls__commands">
        {canClaim ? <form action="/api/bff/workflow/tasks/claim" method="post">
          <MutationHiddenFields
            locale={locale}
            taskId={task.task.id}
            expectedTaskVersion={task.task.recordVersion}
            csrfToken={protection.csrfToken}
            idempotencyKey={protection.claimIdempotencyKey}
          />
          <button type="submit">{text.claim}</button>
        </form> : null}
        {decisions.map((action) => <form
          action="/api/bff/workflow/tasks/decide"
          method="post"
          key={action}
        >
          <MutationHiddenFields
            locale={locale}
            taskId={task.task.id}
            expectedTaskVersion={task.task.recordVersion}
            csrfToken={protection.csrfToken}
            idempotencyKey={protection.decisionIdempotencyKeys[action]}
          />
          <input type="hidden" name="action" value={action} />
          <button type="submit" data-decision={action}>{action === "APPROVE"
            ? text.approve
            : action === "REJECT" ? text.reject : text.requestChanges}</button>
        </form>)}
      </div>
      {canComment ? <form className="workflow-mutation-controls__comment" action="/api/bff/workflow/comments/create" method="post">
        <MutationHiddenFields
          locale={locale}
          taskId={task.task.id}
          expectedTaskVersion={task.task.recordVersion}
          csrfToken={protection.csrfToken}
          idempotencyKey={protection.commentIdempotencyKey}
        />
        <input type="hidden" name="expectedInstanceVersion" value={String(instance.recordVersion)} />
        <label htmlFor="workflow-comment-text">{text.commentLabel}</label>
        <textarea
          id="workflow-comment-text"
          name="commentText"
          minLength={1}
          maxLength={8_192}
          placeholder={text.commentPlaceholder}
          required
        />
        <button type="submit">{text.createComment}</button>
      </form> : null}
    </div>
  );
}

function MutationHiddenFields({
  locale,
  taskId,
  expectedTaskVersion,
  csrfToken,
  idempotencyKey,
}: {
  readonly locale: Locale;
  readonly taskId: string;
  readonly expectedTaskVersion: number;
  readonly csrfToken: string;
  readonly idempotencyKey: string;
}) {
  return <>
    <input type="hidden" name="locale" value={locale} />
    <input type="hidden" name="taskId" value={taskId} />
    <input type="hidden" name="expectedTaskVersion" value={String(expectedTaskVersion)} />
    <input type="hidden" name="csrfToken" value={csrfToken} />
    <input type="hidden" name="idempotencyKey" value={idempotencyKey} />
  </>;
}

function FormBoundary({ locale, task, form, unavailable }: {
  readonly locale: Locale;
  readonly task: ConsoleWorkflowTaskDetail;
  readonly form: ConsoleWorkflowTaskFormSummary | null;
  readonly unavailable: boolean;
}) {
  const text = copy[locale];
  return (
    <section className="workflow-form-boundary">
      <header><span>03</span><div><h3>{text.form}</h3><p>{text.formBoundary}</p></div></header>
      {task.formId === null ? <p>{text.noForm}</p> : unavailable || !form ? <ClosedPanel message={text.formUnavailable} /> : (
        <dl>
          <div><dt>ID / {text.version}</dt><dd>{form.formId}<small>{form.version}</small></dd></div>
          <div><dt>{text.dialect}</dt><dd>{form.schemaDialect}</dd></div>
          <div><dt>SCHEMA SHA-256</dt><dd title={form.schemaDigest}>{shortDigest(form.schemaDigest)}</dd></div>
          <div><dt>UI SHA-256</dt><dd title={form.uiSchemaDigest ?? undefined}>{form.uiSchemaDigest ? shortDigest(form.uiSchemaDigest) : "—"}</dd></div>
          <div><dt>{text.projectedData}</dt><dd>{form.hasProjectedData ? text.present : text.absent}</dd></div>
        </dl>
      )}
    </section>
  );
}

function HistoryRail(props: WorkflowWorkbenchProps) {
  const text = copy[props.locale];
  const cursors = props.cursors ?? {};
  return (
    <section className="workflow-history-rail" aria-labelledby="workflow-history-title">
      <header><span>04</span><h3 id="workflow-history-title">{text.timeline}</h3></header>
      {props.historyUnavailable ? <ClosedPanel message={text.historyUnavailable} /> : props.history?.items.length === 0 ? (
        <p className="workflow-workbench__empty">{text.emptyHistory}</p>
      ) : (
        <ol>{props.history?.items.map((event) => (
          <li key={event.sequence}>
            <i aria-hidden="true" />
            <div><header><strong>{event.eventType}</strong><span>{text.sequence} {event.sequence}</span></header><p>{event.state}</p><small>{event.performedByCurrentUser ? text.currentUser : text.systemOrOther}{event.reasonCode ? ` · ${text.reason}: ${event.reasonCode}` : ""}</small><time dateTime={isoTime(event.occurredAt)}>{formatTime(event.occurredAt, props.locale)}</time></div>
          </li>
        ))}</ol>
      )}
      {props.history?.nextCursor && props.selectedTaskId ? <Link className="workflow-workbench__next" rel="next" href={workflowHref(props.locale, {
        taskId: props.selectedTaskId,
        historyCursor: props.history.nextCursor,
        ...(cursors.comment ? { commentCursor: cursors.comment } : {}),
        ...(props.selectedDefinitionId ? { definitionId: props.selectedDefinitionId } : {}),
        ...(cursors.task ? { taskCursor: cursors.task } : {}),
        ...(cursors.definition ? { definitionCursor: cursors.definition } : {}),
      })}>{text.nextHistory}<span aria-hidden="true">→</span></Link> : null}
    </section>
  );
}

function CommentStream(props: WorkflowWorkbenchProps) {
  const text = copy[props.locale];
  const cursors = props.cursors ?? {};
  return (
    <section className="workflow-comment-stream" aria-labelledby="workflow-comments-title">
      <header><div><span>05</span><h3 id="workflow-comments-title">{text.comments}</h3></div><p>{text.commentsBoundary}</p></header>
      {props.commentsUnavailable ? <ClosedPanel message={text.commentsUnavailable} /> : props.comments?.items.length === 0 ? (
        <p className="workflow-workbench__empty">{text.emptyComments}</p>
      ) : (
        <ol>{props.comments?.items.map((comment) => (
          <li key={comment.id}>
            <header><strong>{comment.authoredByCurrentUser ? text.mine : text.participant}</strong><span>r{comment.revision}</span><time dateTime={isoTime(comment.createdAt)}>{formatTime(comment.createdAt, props.locale)}</time></header>
            <p>{comment.tokens.map((token, index) => token.kind === "TEXT"
              ? <span key={`${comment.id}-text-${index}`}>{token.text}</span>
              : <mark key={`${comment.id}-mention-${index}`}>@{token.displayName}</mark>)}</p>
          </li>
        ))}</ol>
      )}
      {props.comments?.nextCursor && props.selectedTaskId ? <Link className="workflow-workbench__next" rel="next" href={workflowHref(props.locale, {
        taskId: props.selectedTaskId,
        commentCursor: props.comments.nextCursor,
        ...(cursors.history ? { historyCursor: cursors.history } : {}),
        ...(props.selectedDefinitionId ? { definitionId: props.selectedDefinitionId } : {}),
        ...(cursors.task ? { taskCursor: cursors.task } : {}),
        ...(cursors.definition ? { definitionCursor: cursors.definition } : {}),
      })}>{text.nextComments}<span aria-hidden="true">→</span></Link> : null}
    </section>
  );
}

function DefinitionLedger(props: WorkflowWorkbenchProps) {
  const text = copy[props.locale];
  const cursors = props.cursors ?? {};
  return (
    <section className="workflow-definition-ledger" aria-labelledby="workflow-definitions-title">
      <header><div><span>06</span><h2 id="workflow-definitions-title">{text.definitions}</h2></div><p>{text.definitionBoundary}</p></header>
      {props.definitionCapabilityUnavailable ? <ClosedPanel message={text.definitionUnavailable} /> : (
        <div className="workflow-definition-ledger__grid">
          <div className="workflow-definition-ledger__list">
            {props.definitions?.items.length === 0 ? <p className="workflow-workbench__empty">{text.emptyDefinitions}</p> : (
              <ol>{props.definitions?.items.map((definition) => (
                <li key={definition.id} data-selected={definition.id === props.selectedDefinitionId}>
                  <Link href={workflowHref(props.locale, {
                    definitionId: definition.id,
                    ...(props.selectedTaskId ? { taskId: props.selectedTaskId } : {}),
                    ...(cursors.task ? { taskCursor: cursors.task } : {}),
                    ...(cursors.definition ? { definitionCursor: cursors.definition } : {}),
                    ...(props.selectedTaskId && cursors.history ? { historyCursor: cursors.history } : {}),
                    ...(props.selectedTaskId && cursors.comment ? { commentCursor: cursors.comment } : {}),
                  })} aria-current={definition.id === props.selectedDefinitionId ? "page" : undefined}>
                    <div><strong>{definition.title}</strong><code>{definition.key} / {definition.version}</code></div><StatusBadge tone={stateTone(definition.status)}>{definition.status}</StatusBadge>
                  </Link>
                </li>
              ))}</ol>
            )}
            {props.definitions?.nextCursor ? <Link className="workflow-workbench__next" rel="next" href={workflowHref(props.locale, {
              definitionCursor: props.definitions.nextCursor,
              ...(props.selectedTaskId ? { taskId: props.selectedTaskId } : {}),
              ...(props.selectedDefinitionId ? { definitionId: props.selectedDefinitionId } : {}),
              ...(cursors.task ? { taskCursor: cursors.task } : {}),
              ...(props.selectedTaskId && cursors.history ? { historyCursor: cursors.history } : {}),
              ...(props.selectedTaskId && cursors.comment ? { commentCursor: cursors.comment } : {}),
            })}>{text.nextDefinitions}<span aria-hidden="true">→</span></Link> : null}
          </div>
          <DefinitionDossier locale={props.locale} detail={props.definitionDetail} unavailable={props.selectedDefinitionUnavailable ?? false} />
        </div>
      )}
    </section>
  );
}

function DefinitionDossier({ locale, detail, unavailable }: {
  readonly locale: Locale;
  readonly detail: ConsoleWorkflowDefinitionDetail | null;
  readonly unavailable: boolean;
}) {
  const text = copy[locale];
  if (unavailable) return <aside className="workflow-definition-dossier"><ClosedPanel message={text.hiddenDefinition} /></aside>;
  if (!detail) return <aside className="workflow-definition-dossier workflow-definition-dossier--empty"><span aria-hidden="true">↳</span><p>{text.selectDefinition}</p></aside>;
  return (
    <aside className="workflow-definition-dossier">
      <header><div><strong>{detail.summary.title}</strong><code>{detail.summary.id}</code></div><StatusBadge tone={stateTone(detail.summary.status)}>{detail.summary.status}</StatusBadge></header>
      <dl>
        <div><dt>{text.codec}</dt><dd>{detail.codecId}<small>{detail.codecVersion}</small></dd></div>
        <div><dt>{text.recordVersion}</dt><dd>v{detail.summary.recordVersion}</dd></div>
        <div><dt>{text.contentDigest}</dt><dd title={detail.summary.contentDigest}>{shortDigest(detail.summary.contentDigest)}</dd></div>
        <div><dt>{text.sourceDigest}</dt><dd title={detail.sourceDigest}>{shortDigest(detail.sourceDigest)}</dd></div>
      </dl>
      <section><h3>{text.diagnostics}</h3>{detail.diagnostics.length === 0 ? <p>{text.noDiagnostics}</p> : <ul>{detail.diagnostics.map((diagnostic, index) => <li key={`${diagnostic.code}-${diagnostic.nodeId ?? index}`}><strong>{diagnostic.severity}</strong><span>{diagnostic.code}</span>{diagnostic.nodeId ? <code>{diagnostic.nodeId}</code> : null}</li>)}</ul>}</section>
    </aside>
  );
}

function ClosedPanel({ message }: { readonly message: string }) {
  return <div className="workflow-workbench__closed"><span aria-hidden="true">×</span><p>{message}</p></div>;
}

function workflowHref(locale: Locale, values: Readonly<Record<string, string | undefined>>): Route {
  const parameters = new URLSearchParams();
  Object.entries(values).forEach(([name, value]) => { if (value) parameters.set(name, value); });
  const query = parameters.toString();
  return `/${locale}/approvals${query ? `?${query}` : ""}` as Route;
}

function stateTone(state: string): StatusTone {
  if (/^(?:ACTIVE|AVAILABLE|CLAIMED|COMPLETED|PUBLISHED|READY)$/u.test(state)) return "ready";
  if (/^(?:FAILED|ERROR|TERMINATED|REJECTED|CANCELLED)$/u.test(state)) return "error";
  if (/^(?:SUSPENDED|WAITING|PENDING|DRAFT|RETIRED)$/u.test(state)) return "warning";
  return "muted";
}

function formatTime(value: number, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(new Date(value));
}

function isoTime(value: number): string {
  return new Date(value).toISOString();
}

function shortDigest(value: string): string {
  return `${value.slice(0, 12)}…${value.slice(-6)}`;
}
