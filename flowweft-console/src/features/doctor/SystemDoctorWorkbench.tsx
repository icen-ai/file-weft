import { StatusBadge, type StatusTone } from "@/components/ui/StatusBadge";
import type { ConsoleDoctorStatus, ConsoleSystemDoctorReport } from "@/contracts/bff";
import type { Locale } from "@/i18n/locale";

export interface SystemDoctorWorkbenchProps {
  readonly locale: Locale;
  readonly report: ConsoleSystemDoctorReport;
}

const copy = {
  zh: {
    eyebrow: "03 / 实时系统诊断",
    title: "Doctor 运行台",
    summary: "报告由当前服务端会话按可信租户实时生成。这里只展示公共脱敏检查，不展示路径、端点、凭据或原始异常。",
    status: "综合状态",
    inspected: "检查时间",
    checks: "检查项",
    repair: "修复建议",
    noRepair: "当前无需人工修复。",
    empty: "本次报告没有公开检查项。",
  },
  en: {
    eyebrow: "03 / LIVE SYSTEM DIAGNOSTICS",
    title: "Doctor operations desk",
    summary: "The current server session produces this report in its trusted tenant scope. Only redacted public checks are shown—never paths, endpoints, credentials, or raw exceptions.",
    status: "Aggregate status",
    inspected: "Inspected",
    checks: "Checks",
    repair: "Repair guidance",
    noRepair: "No operator repair is required now.",
    empty: "This report contains no public checks.",
  },
} as const;

export function SystemDoctorWorkbench({ locale, report }: SystemDoctorWorkbenchProps) {
  const messages = copy[locale];
  const inspected = new Date(report.inspectedTime);
  return (
    <article className="doctor-workbench">
      <header className="doctor-workbench__hero">
        <div>
          <p className="eyebrow">{messages.eyebrow}</p>
          <h1>{messages.title}</h1>
          <p>{messages.summary}</p>
        </div>
        <div className={`doctor-workbench__summary doctor-workbench__summary--${report.status.toLowerCase()}`}>
          <span>{messages.status}</span>
          <strong>{report.status}</strong>
          <StatusBadge tone={toneForDoctorStatus(report.status)}>{report.checks.length} {messages.checks}</StatusBadge>
        </div>
      </header>

      <section className="doctor-workbench__timestamp" aria-label={messages.inspected}>
        <span>{messages.inspected}</span>
        <time dateTime={inspected.toISOString()}>
          {new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : "en", {
            dateStyle: "full",
            timeStyle: "long",
            timeZone: "UTC",
          }).format(inspected)} UTC
        </time>
      </section>

      {report.checks.length === 0 ? (
        <section className="doctor-workbench__empty">
          <StatusBadge tone="muted">SKIPPED</StatusBadge>
          <p>{messages.empty}</p>
        </section>
      ) : (
        <section className="doctor-workbench__grid" aria-label={messages.checks}>
          {report.checks.map((check, index) => (
            <article className={`doctor-check doctor-check--${check.status.toLowerCase()}`} key={check.checkerName}>
              <header>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <h2>{check.checkerName}</h2>
                <StatusBadge tone={toneForDoctorStatus(check.status)}>{check.status}</StatusBadge>
              </header>
              <p>{check.reason}</p>
              <footer>
                <span>{messages.repair}</span>
                <strong>{check.repairSuggestion ?? messages.noRepair}</strong>
              </footer>
            </article>
          ))}
        </section>
      )}
    </article>
  );
}

function toneForDoctorStatus(status: ConsoleDoctorStatus): StatusTone {
  if (status === "HEALTHY") return "ready";
  if (status === "WARNING") return "warning";
  if (status === "ERROR") return "error";
  return "muted";
}
