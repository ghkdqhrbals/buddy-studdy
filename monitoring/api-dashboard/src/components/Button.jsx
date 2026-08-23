export function Button({
  children,
  icon: Icon,
  busy = false,
  variant = "primary",
  className = "",
  ...props
}) {
  return (
    <button
      className={`button button-${variant} ${className}`.trim()}
      disabled={busy || props.disabled}
      {...props}
    >
      {Icon ? <Icon size={16} strokeWidth={2} aria-hidden="true" /> : null}
      <span>{busy ? "Working..." : children}</span>
    </button>
  );
}
