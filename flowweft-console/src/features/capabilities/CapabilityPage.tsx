import { StatusBadge } from "@/components/ui/StatusBadge";
import type { CapabilityPageId } from "@/config/navigation";
import { capabilityDefinitions } from "@/features/capabilities/definitions";
import type { Locale } from "@/i18n/locale";
import { getMessages } from "@/i18n/messages";

export interface CapabilityPageProps {
  readonly id: CapabilityPageId;
  readonly locale: Locale;
}

export function CapabilityPage({ id, locale }: CapabilityPageProps) {
  const messages = getMessages(locale);
  const page = messages.pages[id];
  const definition = capabilityDefinitions[id];

  return (
    <article className={`capability-page capability-page--${definition.tone}`}>
      <header className="capability-hero">
        <div className="capability-hero__copy">
          <p className="eyebrow">{page.eyebrow}</p>
          <h1>{page.title}</h1>
          <p className="capability-hero__summary">{page.summary}</p>
        </div>
        <div className="capability-stamp" aria-hidden="true">
          <span>{definition.marker}</span>
          <strong>{definition.ledger}</strong>
        </div>
      </header>

      <section className="capability-status" aria-labelledby={`${id}-scope`}>
        <div>
          <span>{messages.capability.scope}</span>
          <strong id={`${id}-scope`}>{page.deliverable}</strong>
        </div>
        <div>
          <span>{messages.capability.currentState}</span>
          <StatusBadge tone="ready">{messages.status.frameReady}</StatusBadge>
        </div>
        <div>
          <span>{messages.capability.liveSurface}</span>
          <StatusBadge tone="muted">{messages.status.noLiveData}</StatusBadge>
        </div>
      </section>

      <section className="capability-workstreams" aria-labelledby={`${id}-workstreams`}>
        <div className="section-heading">
          <span>01</span>
          <h2 id={`${id}-workstreams`}>{messages.capability.workstreams}</h2>
        </div>
        <div className="capability-card-grid">
          {page.cards.map((card, index) => (
            <article className="capability-card" key={card.title}>
              <span className="capability-card__index">0{index + 1}</span>
              <h3>{card.title}</h3>
              <p>{card.detail}</p>
              <small>{card.boundary}</small>
            </article>
          ))}
        </div>
      </section>

      <div className="capability-bottom-grid">
        <section className="unavailable-panel" aria-labelledby={`${id}-empty`}>
          <div className="unavailable-panel__mesh" aria-hidden="true"><span /><span /><span /><span /></div>
          <div>
            <StatusBadge tone="pending">{messages.status.contractRequired}</StatusBadge>
            <h2 id={`${id}-empty`}>{page.emptyTitle}</h2>
            <p>{page.emptyDetail}</p>
          </div>
        </section>

        <section className="evidence-ledger" aria-labelledby={`${id}-evidence`}>
          <div className="section-heading section-heading--compact">
            <span>02</span>
            <h2 id={`${id}-evidence`}>{messages.capability.evidence}</h2>
          </div>
          <p>{messages.capability.evidenceHint}</p>
          <ol>
            {page.proofs.map((proof) => <li key={proof}>{proof}</li>)}
          </ol>
        </section>
      </div>
    </article>
  );
}
