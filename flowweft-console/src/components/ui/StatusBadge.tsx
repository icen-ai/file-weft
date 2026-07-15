export type StatusTone = "ready" | "pending" | "muted" | "warning" | "error";

export interface StatusBadgeProps {
  readonly children: React.ReactNode;
  readonly tone?: StatusTone;
}

export function StatusBadge({ children, tone = "muted" }: StatusBadgeProps) {
  return (
    <span className={`status-badge status-badge--${tone}`} role="status">
      <span aria-hidden="true" className="status-badge__dot" />
      {children}
    </span>
  );
}
