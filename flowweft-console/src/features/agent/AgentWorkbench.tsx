import Link from "next/link";
import type { Route } from "next";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type {
  ConsoleAgentCitationEvidence,
  ConsoleAgentCitationPage,
  ConsoleAgentConversationDetail,
  ConsoleAgentConversationPage,
  ConsoleAgentEventPage,
  ConsoleAgentMessagePage,
  ConsoleAgentRun,
  ConsoleAgentRunPage,
  ConsoleAgentRunStatus,
} from "@/contracts/bff";
import type { Locale } from "@/i18n/locale";

export interface AgentWorkbenchProps {
  readonly locale: Locale;
  readonly conversations: ConsoleAgentConversationPage;
  readonly conversation: ConsoleAgentConversationDetail | null;
  readonly runs: ConsoleAgentRunPage | null;
  readonly run: ConsoleAgentRun | null;
  readonly messages: ConsoleAgentMessagePage | null;
  readonly events: ConsoleAgentEventPage | null;
  readonly citations: ConsoleAgentCitationPage | null;
  readonly selectedConversationUnavailable?: boolean;
  readonly selectedRunUnavailable?: boolean;
  readonly cursors?: AgentWorkbenchCursors;
}

export interface AgentWorkbenchCursors {
  readonly conversation?: string;
  readonly run?: string;
  readonly message?: string;
  readonly event?: string;
  readonly citation?: string;
}

const copy = {
  zh: {
    eyebrow: "06 / 实时智能编织台",
    title: "Agent 对话工作台",
    summary: "对话、运行、消息与引用都来自当前服务端会话的实时授权投影。页面只展示用户与助手可见文本，不接收系统提示、工具参数、Provider 载荷或租户标识。",
    live: "LIVE / AUTHORIZED",
    conversations: "对话卷轴",
    pageCount: "本页对话",
    emptyConversations: "当前身份没有可见对话。",
    selectConversation: "选择一条对话，展开耐久运行证据。",
    hiddenConversation: "所选对话不可见或暂时不可用；系统不区分不存在与无权限。",
    latest: "最新运行",
    updated: "更新时间",
    created: "创建时间",
    nextConversations: "下一页对话",
    dossier: "对话案卷",
    capability: "默认能力",
    stateVersion: "状态版本",
    defaultBudget: "默认预算",
    runs: "耐久运行",
    emptyRuns: "此对话尚无可见运行。",
    selectRun: "选择一个运行，读取消息、恢复事件与引用证据。",
    hiddenRun: "所选运行不可见或暂时不可用；不会回显请求标识或上游状态。",
    nextRuns: "下一页运行",
    runEvidence: "运行证据",
    deadline: "截止时间",
    refresh: "刷新当前证据",
    messages: "授权消息流",
    emptyMessages: "当前页没有可见消息。",
    nextMessages: "后续消息",
    user: "用户",
    assistant: "助手",
    citationMarks: "条引用",
    events: "恢复事件",
    emptyEvents: "当前页没有耐久事件。",
    nextEvents: "后续事件",
    eventSequence: "序列",
    citations: "引用证据账本",
    citationBoundary: "只展示经过前置过滤与当前授权复核的元数据；正文、标题、存储键和 tenant 不进入此 DTO。",
    emptyCitations: "当前页没有授权引用。",
    nextCitations: "后续引用",
    document: "文档",
    version: "版本",
    evidence: "证据",
    page: "页码",
    offsets: "偏移",
    filtered: "复核时间",
    authorization: "授权版本",
    filterReceipt: "过滤回执",
    contentDigest: "内容摘要",
    evidenceDigest: "证据摘要",
    usage: "预算消耗",
    input: "输入 token",
    output: "输出 token",
    models: "模型调用",
    tools: "工具调用",
    duration: "运行时长",
    cost: "成本",
    failure: "安全失败信息",
    unavailableEyebrow: "06 / FAIL-CLOSED AGENT SURFACE",
    unavailableTitle: "Agent 能力当前不可用",
    unavailableDetail: "服务端未返回可验证的对话契约，或当前会话没有该能力。页面不会降级为浏览器直连、模拟回答或未授权搜索。",
    unavailableBoundary: "请由管理员检查 Agent Web Runtime、Provider 能力与当前用户授权；浏览器不会收到内部异常、endpoint 或 secret。",
  },
  en: {
    eyebrow: "06 / LIVE INTELLIGENCE LOOM",
    title: "Agent conversation workbench",
    summary: "Conversations, runs, messages and citations are live, server-authorized projections for the current session. Only user- and assistant-visible text crosses this boundary—never system prompts, tool arguments, provider payloads or tenant identifiers.",
    live: "LIVE / AUTHORIZED",
    conversations: "Conversation reel",
    pageCount: "Visible here",
    emptyConversations: "No conversations are visible to the current principal.",
    selectConversation: "Select a conversation to unfold its durable run evidence.",
    hiddenConversation: "The selected conversation is hidden or temporarily unavailable. Absence and authorization remain indistinguishable.",
    latest: "Latest run",
    updated: "Updated",
    created: "Created",
    nextConversations: "Next conversations",
    dossier: "Conversation dossier",
    capability: "Default capability",
    stateVersion: "State version",
    defaultBudget: "Default budget",
    runs: "Durable runs",
    emptyRuns: "This conversation has no visible runs.",
    selectRun: "Select a run to read messages, recovery events and citation evidence.",
    hiddenRun: "The selected run is hidden or temporarily unavailable. Its requested identifier and upstream status are not echoed.",
    nextRuns: "Next runs",
    runEvidence: "Run evidence",
    deadline: "Deadline",
    refresh: "Refresh current evidence",
    messages: "Authorized message stream",
    emptyMessages: "There are no visible messages on this page.",
    nextMessages: "Later messages",
    user: "User",
    assistant: "Assistant",
    citationMarks: "citations",
    events: "Recovery events",
    emptyEvents: "There are no durable events on this page.",
    nextEvents: "Later events",
    eventSequence: "Sequence",
    citations: "Citation evidence ledger",
    citationBoundary: "Only metadata that passed pre-filtering and current authorization review is shown. Body text, titles, storage keys and tenant never enter this DTO.",
    emptyCitations: "There are no authorized citations on this page.",
    nextCitations: "Later citations",
    document: "Document",
    version: "Version",
    evidence: "Evidence",
    page: "Page",
    offsets: "Offsets",
    filtered: "Reviewed",
    authorization: "Authorization revision",
    filterReceipt: "Filter receipt",
    contentDigest: "Content digest",
    evidenceDigest: "Evidence digest",
    usage: "Budget consumption",
    input: "Input tokens",
    output: "Output tokens",
    models: "Model calls",
    tools: "Tool calls",
    duration: "Duration",
    cost: "Cost",
    failure: "Safe failure detail",
    unavailableEyebrow: "06 / FAIL-CLOSED AGENT SURFACE",
    unavailableTitle: "Agent capability is unavailable",
    unavailableDetail: "The server did not return a verifiable conversation contract, or this session lacks the capability. The page never falls back to browser-direct access, simulated answers or unauthorized search.",
    unavailableBoundary: "An administrator can inspect the Agent Web Runtime, provider capability and current authorization. Internal errors, endpoints and secrets never reach the browser.",
  },
} as const;

export function AgentWorkbenchUnavailable({ locale }: { readonly locale: Locale }) {
  const messages = copy[locale];
  return (
    <article className="agent-unavailable" aria-labelledby="agent-unavailable-title">
      <div className="agent-unavailable__mesh" aria-hidden="true"><i /><i /><i /><i /><i /><i /></div>
      <div>
        <p className="eyebrow">{messages.unavailableEyebrow}</p>
        <h1 id="agent-unavailable-title">{messages.unavailableTitle}</h1>
        <p>{messages.unavailableDetail}</p>
        <strong>{messages.unavailableBoundary}</strong>
      </div>
    </article>
  );
}

export function AgentWorkbench({
  locale,
  conversations,
  conversation,
  runs,
  run,
  messages,
  events,
  citations,
  selectedConversationUnavailable = false,
  selectedRunUnavailable = false,
  cursors = {},
}: AgentWorkbenchProps) {
  const text = copy[locale];
  return (
    <article className="agent-workbench">
      <header className="agent-workbench__hero">
        <div>
          <p className="eyebrow">{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <p>{text.summary}</p>
        </div>
        <div className="agent-workbench__pulse" aria-label={text.live}>
          <i aria-hidden="true" />
          <strong>{text.live}</strong>
          <span>{text.pageCount}</span>
          <b>{String(conversations.items.length).padStart(2, "0")}</b>
        </div>
      </header>

      <div className="agent-workbench__loom">
        <ConversationReel locale={locale} page={conversations} selectedId={conversation?.summary.id ?? null} cursors={cursors} />
        <ConversationDossier
          locale={locale}
          conversation={conversation}
          runs={runs}
          run={run}
          selectedConversationUnavailable={selectedConversationUnavailable}
          selectedRunUnavailable={selectedRunUnavailable}
          cursors={cursors}
        />
      </div>

      {run && messages && events && citations ? (
        <RunEvidence
          locale={locale}
          conversationId={run.conversationId}
          run={run}
          messages={messages}
          events={events}
          citations={citations}
          cursors={cursors}
        />
      ) : null}
    </article>
  );
}

function ConversationReel({ locale, page, selectedId, cursors }: {
  readonly locale: Locale;
  readonly page: ConsoleAgentConversationPage;
  readonly selectedId: string | null;
  readonly cursors: AgentWorkbenchCursors;
}) {
  const text = copy[locale];
  return (
    <section className="agent-conversation-reel" aria-labelledby="agent-conversations-title">
      <header><span>01</span><h2 id="agent-conversations-title">{text.conversations}</h2></header>
      {page.items.length === 0 ? (
        <div className="agent-conversation-reel__empty"><StatusBadge tone="ready">LIVE / EMPTY</StatusBadge><p>{text.emptyConversations}</p></div>
      ) : (
        <ol>
          {page.items.map((item, index) => (
            <li key={item.id} data-selected={selectedId === item.id ? "true" : undefined}>
              <Link href={agentHref(locale, {
                conversationId: item.id,
                ...(cursors.conversation ? { conversationCursor: cursors.conversation } : {}),
              })} aria-current={selectedId === item.id ? "true" : undefined}>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <div><strong>{item.title}</strong><small>{text.updated} · {formatTime(item.updatedAt, locale)}</small></div>
                <StatusBadge tone={toneForRun(item.latestRunStatus)}>{item.latestRunStatus ?? "NO RUN"}</StatusBadge>
              </Link>
            </li>
          ))}
        </ol>
      )}
      {page.nextCursor ? (
        <Link className="agent-workbench__next" rel="next" href={agentHref(locale, {
          conversationCursor: page.nextCursor,
          ...(cursors.run ? { runCursor: cursors.run } : {}),
        })}>{text.nextConversations}<span aria-hidden="true">→</span></Link>
      ) : null}
    </section>
  );
}

function ConversationDossier({
  locale,
  conversation,
  runs,
  run,
  selectedConversationUnavailable,
  selectedRunUnavailable,
  cursors,
}: {
  readonly locale: Locale;
  readonly conversation: ConsoleAgentConversationDetail | null;
  readonly runs: ConsoleAgentRunPage | null;
  readonly run: ConsoleAgentRun | null;
  readonly selectedConversationUnavailable: boolean;
  readonly selectedRunUnavailable: boolean;
  readonly cursors: AgentWorkbenchCursors;
}) {
  const text = copy[locale];
  if (!conversation || !runs) {
    return (
      <section className="agent-conversation-dossier agent-conversation-dossier--empty" aria-live="polite">
        <span aria-hidden="true">{selectedConversationUnavailable ? "×" : "↳"}</span>
        <p className="eyebrow">{text.dossier}</p>
        <h2>{selectedConversationUnavailable ? text.hiddenConversation : text.selectConversation}</h2>
      </section>
    );
  }
  return (
    <section className="agent-conversation-dossier" aria-labelledby="agent-dossier-title">
      <header>
        <div><p className="eyebrow">{text.dossier}</p><h2 id="agent-dossier-title">{conversation.summary.title}</h2><code>{conversation.summary.id}</code></div>
        <StatusBadge tone={toneForRun(conversation.summary.latestRunStatus)}>{conversation.summary.latestRunStatus ?? "READY"}</StatusBadge>
      </header>
      <dl className="agent-conversation-dossier__facts">
        <div><dt>{text.capability}</dt><dd>{conversation.defaultCapabilityId}</dd></div>
        <div><dt>{text.stateVersion}</dt><dd>v{conversation.summary.stateVersion}</dd></div>
        <div><dt>{text.created}</dt><dd><time dateTime={isoTime(conversation.summary.createdAt)}>{formatTime(conversation.summary.createdAt, locale)}</time></dd></div>
      </dl>
      <BudgetStrip locale={locale} budget={conversation.defaultBudget} />
      <div className="agent-run-reel">
        <div className="agent-run-reel__heading"><span>02</span><h3>{text.runs}</h3></div>
        {runs.items.length === 0 ? <p className="agent-run-reel__empty">{text.emptyRuns}</p> : (
          <ol>
            {runs.items.map((item) => (
              <li key={item.id} data-selected={run?.id === item.id ? "true" : undefined}>
                <Link href={agentHref(locale, {
                  conversationId: conversation.summary.id,
                  runId: item.id,
                  ...(cursors.conversation ? { conversationCursor: cursors.conversation } : {}),
                  ...(cursors.run ? { runCursor: cursors.run } : {}),
                })}>
                  <StatusBadge tone={toneForRun(item.status)}>{item.status}</StatusBadge>
                  <span><strong>{item.capabilityId}</strong><small>{formatTime(item.updatedAt, locale)} · v{item.stateVersion}</small></span>
                  <b aria-hidden="true">↗</b>
                </Link>
              </li>
            ))}
          </ol>
        )}
        {runs.nextCursor ? (
          <Link className="agent-workbench__next" rel="next" href={agentHref(locale, {
            conversationId: conversation.summary.id,
            runCursor: runs.nextCursor,
            ...(cursors.conversation ? { conversationCursor: cursors.conversation } : {}),
          })}>{text.nextRuns}<span aria-hidden="true">→</span></Link>
        ) : null}
      </div>
      {!run ? (
        <div className="agent-run-reel__selection" aria-live="polite">
          <span aria-hidden="true">{selectedRunUnavailable ? "×" : "↓"}</span>
          <p>{selectedRunUnavailable ? text.hiddenRun : text.selectRun}</p>
        </div>
      ) : null}
    </section>
  );
}

function RunEvidence({ locale, conversationId, run, messages, events, citations, cursors }: {
  readonly locale: Locale;
  readonly conversationId: string;
  readonly run: ConsoleAgentRun;
  readonly messages: ConsoleAgentMessagePage;
  readonly events: ConsoleAgentEventPage;
  readonly citations: ConsoleAgentCitationPage;
  readonly cursors: AgentWorkbenchCursors;
}) {
  const text = copy[locale];
  const base = { conversationId, runId: run.id };
  return (
    <section className="agent-run-evidence" aria-labelledby="agent-run-evidence-title">
      <header className="agent-run-evidence__header">
        <div><p className="eyebrow">03 / {text.runEvidence}</p><h2 id="agent-run-evidence-title">{run.capabilityId}</h2><code>{run.id}</code></div>
        <div><StatusBadge tone={toneForRun(run.status)}>{run.status}</StatusBadge><Link href={agentHref(locale, base)}>{text.refresh}<span aria-hidden="true">↻</span></Link></div>
      </header>
      <div className="agent-run-evidence__summary">
        <dl>
          <div><dt>{text.stateVersion}</dt><dd>v{run.stateVersion}</dd></div>
          <div><dt>{text.updated}</dt><dd><time dateTime={isoTime(run.updatedAt)}>{formatTime(run.updatedAt, locale)}</time></dd></div>
          <div><dt>{text.deadline}</dt><dd><time dateTime={isoTime(run.deadlineAt)}>{formatTime(run.deadlineAt, locale)}</time></dd></div>
        </dl>
        <UsageLedger locale={locale} run={run} />
      </div>
      {run.failure ? (
        <aside className="agent-run-failure"><strong>{text.failure}</strong><span>{run.failure.category} / {run.failure.code}</span>{run.failure.safeMessage ? <p>{run.failure.safeMessage}</p> : null}</aside>
      ) : null}
      <div className="agent-run-evidence__grid">
        <MessageStream locale={locale} conversationId={conversationId} run={run} page={messages} cursors={cursors} />
        <EventRail locale={locale} conversationId={conversationId} run={run} page={events} cursors={cursors} />
      </div>
      <CitationLedger locale={locale} conversationId={conversationId} run={run} page={citations} cursors={cursors} />
    </section>
  );
}

function MessageStream({ locale, conversationId, run, page, cursors }: {
  readonly locale: Locale;
  readonly conversationId: string;
  readonly run: ConsoleAgentRun;
  readonly page: ConsoleAgentMessagePage;
  readonly cursors: AgentWorkbenchCursors;
}) {
  const text = copy[locale];
  return (
    <section className="agent-message-stream" aria-labelledby="agent-messages-title">
      <div className="agent-run-section-heading"><span>04</span><h3 id="agent-messages-title">{text.messages}</h3></div>
      {page.items.length === 0 ? <p className="agent-run-section-empty">{text.emptyMessages}</p> : (
        <ol>
          {page.items.map((message) => (
            <li key={message.id} className={`agent-message agent-message--${message.role.toLowerCase()}`}>
              <header><span>{String(message.sequence).padStart(3, "0")}</span><strong>{message.role === "USER" ? text.user : text.assistant}</strong><time dateTime={isoTime(message.createdAt)}>{formatTime(message.createdAt, locale)}</time></header>
              <p>{message.authorizedDisplayText}</p>
              {message.citations.length > 0 ? <footer>{message.citations.map((citation) => <code key={citation.id}>[{shortId(citation.id)}]</code>)}<span>{message.citations.length} {text.citationMarks}</span></footer> : null}
            </li>
          ))}
        </ol>
      )}
      {page.nextCursor ? <Link className="agent-workbench__next" rel="next" href={agentHref(locale, {
        conversationId,
        runId: run.id,
        messageCursor: page.nextCursor,
        ...(cursors.event ? { eventCursor: cursors.event } : {}),
        ...(cursors.citation ? { citationCursor: cursors.citation } : {}),
      })}>{text.nextMessages}<span aria-hidden="true">→</span></Link> : null}
    </section>
  );
}

function EventRail({ locale, conversationId, run, page, cursors }: {
  readonly locale: Locale;
  readonly conversationId: string;
  readonly run: ConsoleAgentRun;
  readonly page: ConsoleAgentEventPage;
  readonly cursors: AgentWorkbenchCursors;
}) {
  const text = copy[locale];
  return (
    <aside className="agent-event-rail" aria-labelledby="agent-events-title">
      <div className="agent-run-section-heading"><span>05</span><h3 id="agent-events-title">{text.events}</h3></div>
      {page.items.length === 0 ? <p className="agent-run-section-empty">{text.emptyEvents}</p> : (
        <ol>
          {page.items.map((event) => (
            <li key={event.sequence}>
              <i aria-hidden="true" />
              <div><header><strong>{event.type}</strong><span>{text.eventSequence} {event.sequence}</span></header><p>{event.status ?? event.safeCode ?? `v${event.stateVersion}`}</p><time dateTime={isoTime(event.occurredAt)}>{formatTime(event.occurredAt, locale)}</time></div>
            </li>
          ))}
        </ol>
      )}
      {page.nextCursor ? <Link className="agent-workbench__next" rel="next" href={agentHref(locale, {
        conversationId,
        runId: run.id,
        eventCursor: page.nextCursor,
        ...(cursors.message ? { messageCursor: cursors.message } : {}),
        ...(cursors.citation ? { citationCursor: cursors.citation } : {}),
      })}>{text.nextEvents}<span aria-hidden="true">→</span></Link> : null}
    </aside>
  );
}

function CitationLedger({ locale, conversationId, run, page, cursors }: {
  readonly locale: Locale;
  readonly conversationId: string;
  readonly run: ConsoleAgentRun;
  readonly page: ConsoleAgentCitationPage;
  readonly cursors: AgentWorkbenchCursors;
}) {
  const text = copy[locale];
  return (
    <section className="agent-citation-ledger" aria-labelledby="agent-citations-title">
      <header><div className="agent-run-section-heading"><span>06</span><h3 id="agent-citations-title">{text.citations}</h3></div><p>{text.citationBoundary}</p></header>
      {page.items.length === 0 ? <p className="agent-run-section-empty">{text.emptyCitations}</p> : (
        <ol>
          {page.items.map((citation, index) => <CitationCard key={citation.id} locale={locale} citation={citation} index={index + 1} />)}
        </ol>
      )}
      {page.nextCursor ? <Link className="agent-workbench__next" rel="next" href={agentHref(locale, {
        conversationId,
        runId: run.id,
        citationCursor: page.nextCursor,
        ...(cursors.message ? { messageCursor: cursors.message } : {}),
        ...(cursors.event ? { eventCursor: cursors.event } : {}),
      })}>{text.nextCitations}<span aria-hidden="true">→</span></Link> : null}
    </section>
  );
}

function CitationCard({ locale, citation, index }: {
  readonly locale: Locale;
  readonly citation: ConsoleAgentCitationEvidence;
  readonly index: number;
}) {
  const text = copy[locale];
  return (
    <li>
      <span className="agent-citation-card__index">C{String(index).padStart(2, "0")}</span>
      <div className="agent-citation-card__identity"><strong>{text.document}</strong><code>{citation.documentId}</code><small>{text.version} · {citation.documentVersionId}</small></div>
      <dl>
        <div><dt>{text.evidence}</dt><dd>{citation.evidenceId}</dd></div>
        <div><dt>{text.page}</dt><dd>{citation.pageNumber ?? "—"}</dd></div>
        <div><dt>{text.offsets}</dt><dd>{citation.startOffset === null ? "—" : `${citation.startOffset}–${citation.endOffset}`}</dd></div>
        <div><dt>{text.filtered}</dt><dd><time dateTime={isoTime(citation.filteredAt)}>{formatTime(citation.filteredAt, locale)}</time></dd></div>
        <div><dt>{text.authorization}</dt><dd>{citation.authorizationRevision}</dd></div>
        <div><dt>{text.filterReceipt}</dt><dd title={citation.securityFilterReceiptDigest}>{shortDigest(citation.securityFilterReceiptDigest)}</dd></div>
        <div><dt>{text.contentDigest}</dt><dd title={citation.contentDigest}>{shortDigest(citation.contentDigest)}</dd></div>
        <div><dt>{text.evidenceDigest}</dt><dd title={citation.evidenceDigest}>{shortDigest(citation.evidenceDigest)}</dd></div>
      </dl>
    </li>
  );
}

function BudgetStrip({ locale, budget }: {
  readonly locale: Locale;
  readonly budget: ConsoleAgentConversationDetail["defaultBudget"];
}) {
  const text = copy[locale];
  return (
    <div className="agent-budget-strip" aria-label={text.defaultBudget}>
      <span>{text.input}<strong>{formatNumber(budget.maximumInputTokens, locale)}</strong></span>
      <span>{text.output}<strong>{formatNumber(budget.maximumOutputTokens, locale)}</strong></span>
      <span>{text.models}<strong>{budget.maximumModelCalls}</strong></span>
      <span>{text.tools}<strong>{budget.maximumToolCalls}</strong></span>
    </div>
  );
}

function UsageLedger({ locale, run }: { readonly locale: Locale; readonly run: ConsoleAgentRun }) {
  const text = copy[locale];
  const entries = [
    [text.input, run.usage.inputTokens, run.budget.maximumInputTokens],
    [text.output, run.usage.outputTokens, run.budget.maximumOutputTokens],
    [text.models, run.usage.modelCalls, run.budget.maximumModelCalls],
    [text.tools, run.usage.toolCalls, run.budget.maximumToolCalls],
  ] as const;
  return (
    <div className="agent-usage-ledger" aria-label={text.usage}>
      {entries.map(([label, used, maximum]) => (
        <div key={label}><span>{label}</span><strong>{formatNumber(used, locale)} / {formatNumber(maximum, locale)}</strong><progress aria-label={label} max={Math.max(maximum, 1)} value={Math.min(used, Math.max(maximum, 1))} /></div>
      ))}
      <div><span>{text.duration}</span><strong>{formatDuration(run.usage.durationMillis, locale)}</strong></div>
      <div><span>{text.cost}</span><strong>{formatCost(run.usage.costMicros, locale)}</strong></div>
    </div>
  );
}

function agentHref(locale: Locale, values: Readonly<Record<string, string | undefined>>): Route {
  const parameters = new URLSearchParams();
  Object.entries(values).forEach(([name, value]) => {
    if (value) parameters.set(name, value);
  });
  const query = parameters.toString();
  return `/${locale}/agent${query ? `?${query}` : ""}` as Route;
}

function toneForRun(status: ConsoleAgentRunStatus | null): StatusTone {
  if (status === "COMPLETED") return "ready";
  if (status === "FAILED" || status === "EXPIRED") return "error";
  if (status === "CANCELLED") return "muted";
  if (status === "WAITING_APPROVAL" || status === "WAITING_TOOL") return "warning";
  return status ? "pending" : "muted";
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

function formatNumber(value: number, locale: Locale): string {
  return new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en").format(value);
}

function formatDuration(milliseconds: number, locale: Locale): string {
  if (milliseconds < 1_000) return `${milliseconds} ms`;
  return `${new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en", { maximumFractionDigits: 1 }).format(milliseconds / 1_000)} s`;
}

function formatCost(micros: number, locale: Locale): string {
  const value = new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en", { maximumFractionDigits: 4 })
    .format(micros / 1_000_000);
  return `${value} ${locale === "zh" ? "计费单位" : "units"}`;
}

function shortDigest(value: string): string {
  return `${value.slice(0, 12)}…${value.slice(-6)}`;
}

function shortId(value: string): string {
  return value.length > 16 ? `${value.slice(0, 8)}…` : value;
}
