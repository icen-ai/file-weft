import Link from "next/link";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { ConsoleApprovalInboxPage } from "@/contracts/bff";
import type { Locale } from "@/i18n/locale";

export interface ApprovalInboxProps {
  readonly locale: Locale;
  readonly page: ConsoleApprovalInboxPage;
}

const copy = {
  zh: {
    eyebrow: "02 / 当前用户任务箱",
    title: "审批任务中心",
    summary: "任务由当前宿主身份实时查询。FlowWeft 不返回其他办理人的用户标识或审批评论，也不把幂等记录当成授权缓存。",
    pageCount: "本页可办理",
    trustedTotal: "可信总数",
    workflow: "流程",
    document: "文档",
    state: "任务 / 流程状态",
    assignment: "办理资格",
    assigned: "已分配给我",
    unassigned: "当前可领取",
    updated: "最后更新",
    empty: "当前授权范围内没有待处理任务。",
    next: "下一页任务",
  },
  en: {
    eyebrow: "02 / CURRENT PRINCIPAL INBOX",
    title: "Approval task center",
    summary: "Tasks are queried live for the current host identity. FlowWeft omits other assignee identifiers and review comments, and never treats idempotency as cached authorization.",
    pageCount: "Actionable here",
    trustedTotal: "Trusted total",
    workflow: "Workflow",
    document: "Document",
    state: "Task / workflow state",
    assignment: "Action authority",
    assigned: "Assigned to me",
    unassigned: "Available to claim",
    updated: "Last updated",
    empty: "No pending tasks are visible to the current principal.",
    next: "Next tasks",
  },
} as const;

export function ApprovalInbox({ locale, page }: ApprovalInboxProps) {
  const messages = copy[locale];
  return (
    <article className="approval-inbox">
      <header className="approval-inbox__hero">
        <div>
          <p className="eyebrow">{messages.eyebrow}</p>
          <h1>{messages.title}</h1>
          <p>{messages.summary}</p>
        </div>
        <div className="approval-inbox__count">
          <span>{messages.pageCount}</span>
          <strong>{String(page.items.length).padStart(2, "0")}</strong>
          <small>{messages.trustedTotal}: {page.total ?? "—"}</small>
        </div>
      </header>

      {page.items.length === 0 ? (
        <section className="approval-inbox__empty">
          <StatusBadge tone="ready">LIVE / EMPTY</StatusBadge>
          <p>{messages.empty}</p>
        </section>
      ) : (
        <section className="approval-inbox__list" aria-label={messages.title}>
          {page.items.map((item, index) => (
            <article className="approval-task" key={item.task.id}>
              <div className="approval-task__index">{String(index + 1).padStart(2, "0")}</div>
              <div className="approval-task__document">
                <span>{messages.document}</span>
                <h2>{item.document.title}</h2>
                <code>{item.document.documentNumber}</code>
              </div>
              <dl>
                <div>
                  <dt>{messages.workflow}</dt>
                  <dd>{item.workflowType}<small>{item.task.workflowId}</small></dd>
                </div>
                <div>
                  <dt>{messages.state}</dt>
                  <dd><StatusBadge tone="warning">{item.task.state}</StatusBadge><small>{item.workflowState}</small></dd>
                </div>
                <div>
                  <dt>{messages.assignment}</dt>
                  <dd>{item.task.assignedToCurrentUser ? messages.assigned : messages.unassigned}</dd>
                </div>
                <div>
                  <dt>{messages.updated}</dt>
                  <dd><time dateTime={new Date(item.task.updatedTime).toISOString()}>
                    {new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : "en", {
                      dateStyle: "medium",
                      timeStyle: "short",
                      timeZone: "UTC",
                    }).format(new Date(item.task.updatedTime))}
                  </time></dd>
                </div>
              </dl>
            </article>
          ))}
        </section>
      )}

      {page.nextCursor ? (
        <nav className="approval-inbox__pagination" aria-label={messages.next}>
          <Link href={`/${locale}/approvals?cursor=${encodeURIComponent(page.nextCursor)}`} rel="next">
            {messages.next}<span aria-hidden="true">→</span>
          </Link>
        </nav>
      ) : null}
    </article>
  );
}
