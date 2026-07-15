import { StatusBadge } from "@/components/ui/StatusBadge";
import type { Locale } from "@/i18n/locale";
import { getMessages } from "@/i18n/messages";

export interface DashboardProps {
  readonly locale: Locale;
}

export function Dashboard({ locale }: DashboardProps) {
  const copy = getMessages(locale);
  const dashboard = copy.dashboard;

  return (
    <article className="dashboard">
      <header className="dashboard-hero">
        <div>
          <p className="eyebrow">{dashboard.eyebrow}</p>
          <h1>{dashboard.title}<br /><em>{dashboard.titleAccent}</em></h1>
          <p>{dashboard.summary}</p>
        </div>
        <div className="dashboard-hero__seal" aria-hidden="true">
          <span>FW</span>
          <strong>1.0</strong>
        </div>
      </header>

      <section className="signal-ledger" aria-label={locale === "zh" ? "接入状态" : "Connection status"}>
        {dashboard.signals.map((signal, index) => (
          <div key={signal.label}>
            <span>0{index + 1} / {signal.label}</span>
            <strong>{signal.detail}</strong>
          </div>
        ))}
      </section>

      <div className="dashboard-grid">
        <section className="weave-diagram" aria-labelledby="weave-title">
          <div className="section-heading section-heading--inverse">
            <span>01</span>
            <h2 id="weave-title">{dashboard.weaveTitle}</h2>
          </div>
          <div className="weave-diagram__field" aria-hidden="true">
            <span className="weave-diagram__label weave-diagram__label--console">CONSOLE</span>
            <span className="weave-diagram__label weave-diagram__label--bff">BFF</span>
            <span className="weave-diagram__label weave-diagram__label--runtime">RUNTIME</span>
            <i /><i /><i /><i /><i />
          </div>
          <p>{dashboard.weaveDetail}</p>
          <StatusBadge tone="warning">{copy.status.contractRequired}</StatusBadge>
        </section>

        <section className="product-lanes" aria-labelledby="lanes-title">
          <div className="section-heading">
            <span>02</span>
            <h2 id="lanes-title">{dashboard.lanesTitle}</h2>
          </div>
          <ol>
            {dashboard.lanes.map((lane) => (
              <li key={lane.code}>
                <span>{lane.code}</span>
                <div><strong>{lane.title}</strong><small>{lane.detail}</small></div>
              </li>
            ))}
          </ol>
        </section>
      </div>

      <section className="dashboard-evidence" aria-labelledby="dashboard-evidence-title">
        <div>
          <p className="eyebrow">03 / EVIDENCE</p>
          <h2 id="dashboard-evidence-title">{dashboard.evidenceTitle}</h2>
        </div>
        <div className="dashboard-evidence__empty">
          <span aria-hidden="true">∅</span>
          <div><strong>{dashboard.evidenceEmpty}</strong><p>{dashboard.evidenceDetail}</p></div>
        </div>
      </section>
    </article>
  );
}
