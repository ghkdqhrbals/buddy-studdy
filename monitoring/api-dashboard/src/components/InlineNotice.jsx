import { AlertCircle, CheckCircle2, Info } from "lucide-react";

const icons = { danger: AlertCircle, success: CheckCircle2, info: Info };

export function InlineNotice({ children, tone = "info", compact = false }) {
  const Icon = icons[tone] || Info;
  return (
    <div className="inline-notice" data-tone={tone} data-compact={compact ? "true" : "false"}>
      <Icon size={16} aria-hidden="true" />
      <span>{children}</span>
    </div>
  );
}
