export interface WeaveMarkProps {
  readonly compact?: boolean;
}

export function WeaveMark({ compact = false }: WeaveMarkProps) {
  return (
    <span className={compact ? "weave-mark weave-mark--compact" : "weave-mark"} aria-hidden="true">
      <span className="weave-mark__warp" />
      <span className="weave-mark__weft" />
      <strong>F/W</strong>
    </span>
  );
}
