import Link from "next/link";
import type { Route } from "next";
import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type {
  ConsoleDocumentDetail,
  ConsoleDocumentPage,
  ConsoleDocumentPageQuery,
  ConsoleDocumentVersion,
} from "@/contracts/bff";
import type { Locale } from "@/i18n/locale";

export interface DocumentWorkbenchProps {
  readonly locale: Locale;
  readonly page: ConsoleDocumentPage;
  readonly query?: Pick<ConsoleDocumentPageQuery, "lifecycleState" | "folderId">;
  readonly selectedDocumentId?: string | null;
  readonly detail?: ConsoleDocumentDetail | null;
  readonly selectionUnavailable?: boolean;
}

const copy = {
  zh: {
    eyebrow: "01 / 可信文档投影",
    title: "文档工作台",
    summary: "目录、生命周期和版本证据均从当前服务端会话读取；tenant 与权限由宿主 token 推导。",
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
    filters: "收窄可信投影",
    lifecycleFilter: "生命周期",
    allStates: "全部状态",
    folderFilter: "目录编号",
    folderPlaceholder: "例如 contracts/legal",
    apply: "应用筛选",
    reset: "清除",
    inspect: "查看版本证据",
    selected: "当前案卷",
    selectPrompt: "从左侧选择文档，以查看服务端验证后的版本链。",
    unavailable: "所选文档不可见或暂时不可用。系统不会区分不存在与无权限。",
    current: "当前版本",
    versions: "版本链",
    file: "文件",
    size: "大小",
    contentType: "内容类型",
    created: "创建时间",
    noVersions: "该文档尚无可见版本。",
  },
  en: {
    eyebrow: "01 / TRUSTED DOCUMENT PROJECTION",
    title: "Document workbench",
    summary: "Catalog, lifecycle and version evidence come from the current server session. Tenant and authority are derived from the host token.",
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
    filters: "Narrow the trusted projection",
    lifecycleFilter: "Lifecycle",
    allStates: "All states",
    folderFilter: "Folder ID",
    folderPlaceholder: "e.g. contracts/legal",
    apply: "Apply filters",
    reset: "Clear",
    inspect: "Inspect version evidence",
    selected: "Selected dossier",
    selectPrompt: "Select a document on the left to inspect its server-validated version chain.",
    unavailable: "The selected document is hidden or temporarily unavailable. Existence and authorization are intentionally indistinguishable.",
    current: "Current version",
    versions: "Version chain",
    file: "File",
    size: "Size",
    contentType: "Content type",
    created: "Created",
    noVersions: "This document has no visible versions.",
  },
} as const;

const lifecycleStates = ["DRAFT", "PENDING_REVIEW", "PUBLISHED", "OFFLINE", "ARCHIVED"] as const;

export function DocumentWorkbench({
  locale,
  page,
  query = {},
  selectedDocumentId = null,
  detail = null,
  selectionUnavailable = false,
}: DocumentWorkbenchProps) {
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

      <form className="document-workbench__filters" method="get">
        <strong>{messages.filters}</strong>
        <label>
          <span>{messages.lifecycleFilter}</span>
          <select name="lifecycleState" defaultValue={query.lifecycleState ?? ""}>
            <option value="">{messages.allStates}</option>
            {lifecycleStates.map((state) => <option key={state} value={state}>{state}</option>)}
          </select>
        </label>
        <label>
          <span>{messages.folderFilter}</span>
          <input
            name="folderId"
            defaultValue={query.folderId ?? ""}
            maxLength={512}
            placeholder={messages.folderPlaceholder}
            autoComplete="off"
          />
        </label>
        <div className="document-workbench__filter-actions">
          <button type="submit">{messages.apply}</button>
          <Link href={`/${locale}/documents`}>{messages.reset}</Link>
        </div>
      </form>

      <div className="document-workbench__workspace">
        <section className="document-workbench__catalog" aria-label={messages.document}>
          {page.items.length === 0 ? (
            <div className="document-workbench__empty">
              <StatusBadge tone="ready">LIVE / EMPTY</StatusBadge>
              <p>{messages.empty}</p>
            </div>
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
                  {page.items.map((document) => {
                    const selected = document.id === selectedDocumentId;
                    return (
                      <tr key={document.id} data-selected={selected ? "true" : undefined}>
                        <td><code>{document.documentNumber}</code></td>
                        <td>
                          <Link
                            href={documentHref(locale, document.id, query)}
                            aria-current={selected ? "true" : undefined}
                          >
                            <strong>{document.title}</strong>
                            <small>{messages.inspect} · {document.id}</small>
                          </Link>
                        </td>
                        <td><StatusBadge tone={toneForLifecycle(document.lifecycleState)}>{document.lifecycleState}</StatusBadge></td>
                        <td><code>{document.folderId ?? messages.noFolder}</code></td>
                        <td><time dateTime={isoTime(document.updatedTime)}>{formatTime(document.updatedTime, locale)}</time></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {page.nextCursor ? (
            <nav className="document-workbench__pagination" aria-label={messages.next}>
              <Link href={pageHref(locale, page.nextCursor, query)} rel="next">
                {messages.next}<span aria-hidden="true">→</span>
              </Link>
            </nav>
          ) : null}
        </section>

        <DocumentDossier
          locale={locale}
          detail={detail}
          selectionUnavailable={selectionUnavailable}
        />
      </div>
    </article>
  );
}

function DocumentDossier({
  locale,
  detail,
  selectionUnavailable,
}: {
  readonly locale: Locale;
  readonly detail: ConsoleDocumentDetail | null;
  readonly selectionUnavailable: boolean;
}) {
  const messages = copy[locale];
  if (!detail) {
    return (
      <aside className="document-dossier document-dossier--empty" aria-live="polite">
        <span aria-hidden="true">{selectionUnavailable ? "×" : "↳"}</span>
        <p className="eyebrow">{messages.selected}</p>
        <h2>{selectionUnavailable ? messages.unavailable : messages.selectPrompt}</h2>
      </aside>
    );
  }
  return (
    <aside className="document-dossier" aria-label={messages.selected}>
      <header>
        <div>
          <p className="eyebrow">{messages.selected}</p>
          <h2>{detail.document.title}</h2>
          <code>{detail.document.documentNumber}</code>
        </div>
        <StatusBadge tone={toneForLifecycle(detail.document.lifecycleState)}>
          {detail.document.lifecycleState}
        </StatusBadge>
      </header>
      <dl className="document-dossier__facts">
        <div><dt>{messages.folder}</dt><dd>{detail.document.folderId ?? messages.noFolder}</dd></div>
        <div><dt>{messages.updated}</dt><dd><time dateTime={isoTime(detail.document.updatedTime)}>{formatTime(detail.document.updatedTime, locale)}</time></dd></div>
        <div><dt>{messages.versions}</dt><dd>{String(detail.versions.length).padStart(2, "0")}</dd></div>
      </dl>
      <ol className="document-dossier__versions">
        {detail.versions.length === 0 ? <li className="document-dossier__no-version">{messages.noVersions}</li> :
          [...detail.versions].sort((left, right) => right.createdTime - left.createdTime).map((version, index) => (
            <VersionEvidence
              key={version.id}
              locale={locale}
              version={version}
              current={version.id === detail.document.currentVersionId}
              index={detail.versions.length - index}
            />
          ))}
      </ol>
    </aside>
  );
}

function VersionEvidence({ locale, version, current, index }: {
  readonly locale: Locale;
  readonly version: ConsoleDocumentVersion;
  readonly current: boolean;
  readonly index: number;
}) {
  const messages = copy[locale];
  return (
    <li data-current={current ? "true" : undefined}>
      <span className="document-dossier__version-index">{String(index).padStart(2, "0")}</span>
      <div>
        <header>
          <strong>{version.versionNumber}</strong>
          {current ? <StatusBadge tone="ready">{messages.current}</StatusBadge> : null}
        </header>
        <dl>
          <div><dt>{messages.file}</dt><dd>{version.fileName}</dd></div>
          <div><dt>{messages.size}</dt><dd>{formatBytes(version.contentLength, locale)}</dd></div>
          <div><dt>{messages.contentType}</dt><dd>{version.contentType ?? "—"}</dd></div>
          <div><dt>{messages.created}</dt><dd><time dateTime={isoTime(version.createdTime)}>{formatTime(version.createdTime, locale)}</time></dd></div>
        </dl>
      </div>
    </li>
  );
}

function documentHref(
  locale: Locale,
  documentId: string,
  query: Pick<ConsoleDocumentPageQuery, "lifecycleState" | "folderId">,
): Route {
  const parameters = filterParameters(query);
  parameters.set("documentId", documentId);
  return `/${locale}/documents?${parameters.toString()}` as Route;
}

function pageHref(
  locale: Locale,
  cursor: string,
  query: Pick<ConsoleDocumentPageQuery, "lifecycleState" | "folderId">,
): Route {
  const parameters = filterParameters(query);
  parameters.set("cursor", cursor);
  return `/${locale}/documents?${parameters.toString()}` as Route;
}

function filterParameters(query: Pick<ConsoleDocumentPageQuery, "lifecycleState" | "folderId">): URLSearchParams {
  const parameters = new URLSearchParams();
  if (query.lifecycleState) parameters.set("lifecycleState", query.lifecycleState);
  if (query.folderId) parameters.set("folderId", query.folderId);
  return parameters;
}

function toneForLifecycle(state: string): StatusTone {
  if (state === "PUBLISHED") return "ready";
  if (state === "PENDING_REVIEW" || state === "OFFLINE") return "warning";
  if (state === "ARCHIVED") return "muted";
  return "pending";
}

function formatTime(epochMillis: number, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : "en", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(new Date(epochMillis));
}

function isoTime(epochMillis: number): string {
  return new Date(epochMillis).toISOString();
}

function formatBytes(bytes: number, locale: Locale): string {
  if (bytes < 1_024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"] as const;
  let value = bytes / 1_024;
  let unit: (typeof units)[number] = units[0];
  for (let index = 1; index < units.length && value >= 1_024; index += 1) {
    value /= 1_024;
    unit = units[index]!;
  }
  return `${new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en", { maximumFractionDigits: 1 }).format(value)} ${unit}`;
}
