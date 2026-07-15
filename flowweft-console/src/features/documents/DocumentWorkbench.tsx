import Link from "next/link";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type { ConsoleDocumentPage } from "@/contracts/bff";
import type { Locale } from "@/i18n/locale";

export interface DocumentWorkbenchProps {
  readonly locale: Locale;
  readonly page: ConsoleDocumentPage;
}

const copy = {
  zh: {
    eyebrow: "01 / 可信文档投影",
    title: "文档工作台",
    summary: "以下数据由当前服务端会话实时读取；tenant 与权限由宿主 token 推导，浏览器不能覆盖。",
    count: "本页文档",
    total: "可信总数",
    number: "文档编号",
    document: "标题",
    state: "生命周期",
    folder: "目录绑定",
    updated: "最后更新",
    noFolder: "未绑定",
    empty: "当前授权范围内没有文档。",
    next: "下一页",
  },
  en: {
    eyebrow: "01 / TRUSTED DOCUMENT PROJECTION",
    title: "Document workbench",
    summary: "This is live data from the current server session. Tenant and authorization come from the host token and cannot be overridden by the browser.",
    count: "Page records",
    total: "Trusted total",
    number: "Document number",
    document: "Title",
    state: "Lifecycle",
    folder: "Folder binding",
    updated: "Last updated",
    noFolder: "Unbound",
    empty: "No documents are visible to the current principal.",
    next: "Next page",
  },
} as const;

export function DocumentWorkbench({ locale, page }: DocumentWorkbenchProps) {
  const messages = copy[locale];
  return (
    <article className="document-workbench">
      <header className="document-workbench__hero">
        <div>
          <p className="eyebrow">{messages.eyebrow}</p>
          <h1>{messages.title}</h1>
          <p>{messages.summary}</p>
        </div>
        <div className="document-workbench__counts" aria-label={messages.count}>
          <span>{messages.count}</span>
          <strong>{String(page.items.length).padStart(2, "0")}</strong>
          <small>{messages.total}: {page.total === null ? "—" : page.total}</small>
        </div>
      </header>

      {page.items.length === 0 ? (
        <section className="document-workbench__empty">
          <StatusBadge tone="ready">LIVE / EMPTY</StatusBadge>
          <p>{messages.empty}</p>
        </section>
      ) : (
        <div className="document-workbench__table-wrap">
          <table className="document-workbench__table">
            <thead>
              <tr>
                <th scope="col">{messages.number}</th>
                <th scope="col">{messages.document}</th>
                <th scope="col">{messages.state}</th>
                <th scope="col">{messages.folder}</th>
                <th scope="col">{messages.updated}</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((document) => (
                <tr key={document.id}>
                  <td><code>{document.documentNumber}</code></td>
                  <td><strong>{document.title}</strong><small>{document.id}</small></td>
                  <td><StatusBadge tone={toneForLifecycle(document.lifecycleState)}>{document.lifecycleState}</StatusBadge></td>
                  <td><code>{document.folderId ?? messages.noFolder}</code></td>
                  <td><time dateTime={new Date(document.updatedTime).toISOString()}>
                    {new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : "en", {
                      dateStyle: "medium",
                      timeStyle: "short",
                      timeZone: "UTC",
                    }).format(new Date(document.updatedTime))}
                  </time></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {page.nextCursor ? (
        <nav className="document-workbench__pagination" aria-label={messages.next}>
          <Link href={`/${locale}/documents?cursor=${encodeURIComponent(page.nextCursor)}`} rel="next">
            {messages.next}<span aria-hidden="true">→</span>
          </Link>
        </nav>
      ) : null}
    </article>
  );
}

function toneForLifecycle(state: string): StatusTone {
  if (state === "PUBLISHED") {
    return "ready";
  }
  if (state === "PENDING_REVIEW" || state === "OFFLINE") {
    return "warning";
  }
  if (state === "ARCHIVED") {
    return "muted";
  }
  return "pending";
}
