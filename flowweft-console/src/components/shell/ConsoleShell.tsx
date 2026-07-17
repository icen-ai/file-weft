import { WeaveMark } from "@/components/brand/WeaveMark";
import { ConsoleNav } from "@/components/shell/ConsoleNav";
import { LocaleSwitcher } from "@/components/shell/LocaleSwitcher";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { Locale } from "@/i18n/locale";
import { getMessages } from "@/i18n/messages";
import type { ConsoleSessionProjection } from "@/contracts/bff";

export interface ConsoleShellProps {
  readonly children: React.ReactNode;
  readonly locale: Locale;
  readonly session: ConsoleSessionProjection;
}

export function ConsoleShell({ children, locale, session }: ConsoleShellProps) {
  const copy = getMessages(locale);

  return (
    <div className="console-shell" lang={locale === "zh" ? "zh-CN" : "en"}>
      <a className="skip-link" href="#main-content">{copy.shell.skip}</a>
      <aside className="console-rail">
        <div className="console-brand">
          <WeaveMark compact />
          <div>
            <strong>{copy.brand.name}</strong>
            <small>{copy.brand.product}</small>
          </div>
        </div>

        <div className="console-edition">{copy.brand.edition}</div>
        <ConsoleNav
          groupLabels={copy.navGroups}
          labels={copy.nav}
          locale={locale}
        />

        <footer className="console-rail__footer">
          <StatusBadge tone="warning">{copy.shell.foundation}</StatusBadge>
          <p>{copy.shell.foundationDetail}</p>
          <form action="/api/auth/logout" method="post">
            <button type="submit">{copy.shell.logout} <span aria-hidden="true">↗</span></button>
          </form>
        </footer>
      </aside>

      <div className="console-canvas">
        <header className="context-bar">
          <div className="context-bar__pair">
            <span>{copy.shell.sourceLabel}</span>
            <strong>{session.sourceProfileId}</strong>
          </div>
          <div className="context-bar__pair">
            <span>{copy.shell.sessionLabel}</span>
            <strong>{session.subjectDisplayName} / {session.tenantAlias}</strong>
          </div>
          <LocaleSwitcher current={locale} label={copy.shell.language} />
        </header>
        <main className="console-main" id="main-content" tabIndex={-1}>{children}</main>
      </div>
    </div>
  );
}
