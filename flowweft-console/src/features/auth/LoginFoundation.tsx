import { WeaveMark } from "@/components/brand/WeaveMark";
import { LocaleSwitcher } from "@/components/shell/LocaleSwitcher";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { Locale } from "@/i18n/locale";
import { getMessages } from "@/i18n/messages";
import type { SourceProfileSummary } from "@/contracts/bff";

export interface LoginFoundationProps {
  readonly locale: Locale;
  readonly sourceProfiles?: readonly SourceProfileSummary[];
}

export function LoginFoundation({ locale, sourceProfiles = [] }: LoginFoundationProps) {
  const copy = getMessages(locale);
  const login = copy.login;

  return (
    <main className="login-foundation" lang={locale === "zh" ? "zh-CN" : "en"}>
      <a className="skip-link" href="#login-panel">{copy.shell.skip}</a>
      <div className="login-foundation__language">
        <LocaleSwitcher current={locale} label={copy.shell.language} />
      </div>

      <section className="login-manifesto">
        <div className="login-manifesto__brand">
          <WeaveMark />
          <span>{copy.brand.edition}</span>
        </div>
        <div className="login-manifesto__copy">
          <p className="eyebrow">{login.eyebrow}</p>
          <h1>{login.titleLead}<br /><em>{login.titleAccent}</em></h1>
          <p>{login.description}</p>
        </div>
        <div className="login-guardrails">
          {login.guardrails.map((guardrail) => (
            <article key={guardrail.label}>
              <span>{guardrail.label}</span>
              <p>{guardrail.detail}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="login-panel" id="login-panel">
        <header>
          <div>
            <p className="eyebrow">SOURCE / SESSION</p>
            <h2>{login.panelTitle}</h2>
          </div>
          <StatusBadge tone="ready">{login.panelStatus}</StatusBadge>
        </header>

        {sourceProfiles.length === 0 ? (
          <div className="source-profile-empty">
            <span className="source-profile-empty__code">00</span>
            <div>
              <small>{login.sourceTitle}</small>
              <strong>{login.sourceEmpty}</strong>
              <p>{login.sourceDetail}</p>
            </div>
          </div>
        ) : (
          <section className="source-profile-catalog" aria-labelledby="source-profile-catalog-title">
            <div className="source-profile-catalog__heading">
              <div>
                <small>{login.sourceTitle}</small>
                <strong id="source-profile-catalog-title">{login.sourceConfigured}</strong>
              </div>
              <span>{String(sourceProfiles.length).padStart(2, "0")}</span>
            </div>
            <p>{login.sourceConfiguredDetail}</p>
            <ol>
              {sourceProfiles.map((profile, index) => (
                <li key={profile.id}>
                  <span className="source-profile-catalog__index">{String(index + 1).padStart(2, "0")}</span>
                  <div>
                    <strong>{profile.displayName}</strong>
                    <code>{profile.id}</code>
                    <small>
                      {profile.authenticationModes.map((mode) =>
                        mode === "OIDC_PKCE" ? login.oidcMode : login.hostExchangeMode,
                      ).join(" / ") || login.sourceUnavailable}
                    </small>
                  </div>
                  <StatusBadge tone={profile.state === "AVAILABLE" ? "ready" :
                    profile.state === "UNAVAILABLE" ? "muted" : "warning"}>
                    {profile.state === "AVAILABLE" ? login.sourceReady :
                      profile.state === "UNAVAILABLE" ? login.sourceUnavailable : login.sourceDegraded}
                  </StatusBadge>
                  {profile.state === "AVAILABLE" && profile.authenticationModes.includes("OIDC_PKCE") ? (
                    <form action="/api/auth/oidc/start" className="source-profile-login" method="post">
                      <input name="sourceProfileId" type="hidden" value={profile.id} />
                      <input name="locale" type="hidden" value={locale} />
                      <button type="submit">{login.oidc}<span aria-hidden="true">→</span></button>
                    </form>
                  ) : null}
                  {profile.state === "AVAILABLE" && profile.authenticationModes.includes("HOST_TOKEN_EXCHANGE") ? (
                    <form
                      action="/api/auth/host-exchange"
                      autoComplete="on"
                      className="source-profile-login source-profile-host-login"
                      method="post"
                    >
                      <input name="sourceProfileId" type="hidden" value={profile.id} />
                      <input name="locale" type="hidden" value={locale} />
                      <div className="source-profile-host-login__fields">
                        <label>
                          <span>{login.hostTenant}</span>
                          <input autoCapitalize="none" autoComplete="organization" maxLength={256}
                            name="tenantAlias" required spellCheck={false} type="text" />
                        </label>
                        <label>
                          <span>{login.hostUsername}</span>
                          <input autoCapitalize="none" autoComplete="username" maxLength={256}
                            name="username" required spellCheck={false} type="text" />
                        </label>
                        <label>
                          <span>{login.hostPassword}</span>
                          <input autoComplete="current-password" maxLength={4096}
                            name="password" required type="password" />
                        </label>
                      </div>
                      <p>{login.hostHint}</p>
                      <button type="submit">{login.hostSubmit}<span aria-hidden="true">→</span></button>
                    </form>
                  ) : null}
                </li>
              ))}
            </ol>
          </section>
        )}

        <p className="action-hint">{login.oidcHint}</p>

        <aside className="compatibility-note">
          <strong>{login.compatibilityTitle}</strong>
          <p>{login.compatibilityDetail}</p>
        </aside>

      </section>
    </main>
  );
}
