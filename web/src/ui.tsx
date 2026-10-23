// Shared building blocks so every page looks the same; styles live in styles.css.
import type { ButtonHTMLAttributes, ReactNode } from "react";

// Inline SVG paths (24x24, stroke) so icons need no dependency.
const ICONS = {
  logo: "M12 2 3 7v10l9 5 9-5V7l-9-5Zm0 6v8m-4-6 4-2 4 2",
  folder:
    "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z",
  settings:
    "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-3a7.4 7.4 0 0 0-.1-1.3l2-1.6-2-3.4-2.4 1a7.5 7.5 0 0 0-2.2-1.3L14.3 3h-4l-.4 2.4a7.5 7.5 0 0 0-2.2 1.3l-2.4-1-2 3.4 2 1.6a7.4 7.4 0 0 0 0 2.6l-2 1.6 2 3.4 2.4-1a7.5 7.5 0 0 0 2.2 1.3l.4 2.4h4l.4-2.4a7.5 7.5 0 0 0 2.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3Z",
  logout: "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3",
  play: "m7 4 13 8-13 8V4Z",
  plus: "M12 5v14M5 12h14",
  back: "M19 12H5m6-6-6 6 6 6",
  commit: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM3 12h6m6 0h6",
  branch:
    "M6 3v12m0 0a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm12-6a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm0 0a9 9 0 0 1-9 9",
  external:
    "M14 4h6v6m0-6L10 14M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5",
  check: "M20 6 9 17l-5-5",
  alert:
    "M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z",
  info: "M12 16v-4m0-4h.01M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z",
  sparkles:
    "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3ZM19 17l.8 2.2L22 20l-2.2.8L19 23l-.8-2.2L16 20l2.2-.8L19 17Z",
  cpu: "M9 3v2m6-2v2M9 19v2m6-2v2M3 9h2m-2 6h2m14-6h2m-2 6h2M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Zm2 4h6v6H9V9Z",
  trash: "M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14",
  bug: "M8 9h8v6a4 4 0 0 1-8 0V9Zm4-5a3 3 0 0 1 3 3v2H9V7a3 3 0 0 1 3-3ZM3 13h5m8 0h5M4 7l4 3m12-3-4 3M4 19l4-3m12 3-4-3",
  clock: "M12 7v5l3 2m7-2a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z",
  inbox:
    "M22 12h-6l-2 3h-4l-2-3H2m3.5-6.5L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.5A2 2 0 0 0 16.7 4H7.3a2 2 0 0 0-1.8 1.5Z",
  database:
    "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Zm0 0v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
};

export type IconName = keyof typeof ICONS;

export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={ICONS[name]} />
    </svg>
  );
}

export const Spinner = () => <span className="spinner" aria-hidden="true" />;

type Tone = "neutral" | "info" | "success" | "warn" | "danger" | "ai";

// Every status the backend sends (hunts, experiments, diagnoses), mapped to a color.
const TONE: Record<string, Tone> = {
  CREATED: "warn", // pending: nothing picked it up yet
  PREPARING: "info",
  RUNNING: "info",
  ANALYZING: "info",
  DIAGNOSING: "ai",
  VERIFYING: "ai",
  AWAITING_AI: "warn",
  COMPLETED: "success",
  FAILED: "danger",
  CANCELLED: "neutral",
  REJECTED: "danger",
  FIX_VERIFIED: "success",
  FIX_FAILED: "warn",
  FIX_REJECTED: "warn",
  INVESTIGATED: "info",
  UNKNOWN: "neutral",
  BUDGET_EXCEEDED: "warn",
  ERROR: "danger",
};
const ACTIVE = ["PREPARING", "RUNNING", "ANALYZING", "DIAGNOSING", "VERIFYING"];

/** A colored pill for a backend status; active ones get a spinner. */
export function StatusBadge({
  status,
  label,
}: {
  status: string;
  label?: string;
}) {
  return (
    <Badge tone={TONE[status] ?? "neutral"} busy={ACTIVE.includes(status)}>
      {label ?? status.replaceAll("_", " ").toLowerCase()}
    </Badge>
  );
}

export function Badge({
  tone = "neutral",
  busy,
  children,
}: {
  tone?: Tone;
  busy?: boolean;
  children: ReactNode;
}) {
  return (
    <span className={`badge ${tone}`}>
      {busy ? <Spinner /> : <span className="dot" />}
      {children}
    </span>
  );
}

const ALERT_ICON = {
  error: "alert",
  success: "check",
  info: "info",
  warn: "alert",
} as const;

export function Alert({
  kind = "error",
  children,
}: {
  kind?: keyof typeof ALERT_ICON;
  children: ReactNode;
}) {
  return (
    <div
      className={`alert ${kind}`}
      role={kind === "error" ? "alert" : "status"}
    >
      <Icon name={ALERT_ICON[kind]} />
      <div>{children}</div>
    </div>
  );
}

export function Card({
  title,
  icon,
  actions,
  children,
}: {
  title?: ReactNode;
  icon?: IconName;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="card">
      {title && (
        <header className="card-head">
          <h2>
            {icon && <Icon name={icon} />}
            {title}
          </h2>
          {actions}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({
  title,
  subtitle,
  back,
  actions,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  back?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="page-head">
      {back && <div className="back">{back}</div>}
      <div className="page-head-row">
        <div>
          <h1>{title}</h1>
          {subtitle && <div className="subtitle">{subtitle}</div>}
        </div>
        {actions}
      </div>
    </div>
  );
}

export function Empty({
  icon = "inbox",
  title,
  children,
}: {
  icon?: IconName;
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <Icon name={icon} size={28} />
      <strong>{title}</strong>
      {children && <p>{children}</p>}
    </div>
  );
}

/** value is null for an indeterminate bar (work started, size not known yet). */
export function Progress({
  value,
  max,
  label,
}: {
  value: number | null;
  max: number;
  label: string;
}) {
  const pct = value == null || !max ? null : Math.min(100, (value / max) * 100);
  return (
    <div
      className={`progress${pct == null ? " indeterminate" : ""}`}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value ?? undefined}
    >
      <div style={pct == null ? undefined : { width: `${pct}%` }} />
    </div>
  );
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="skeleton-group" aria-busy="true" aria-label="Loading">
      {Array.from({ length: lines }, (_, i) => (
        <div
          key={i}
          className="skeleton"
          style={{ width: `${90 - i * 15}%` }}
        />
      ))}
    </div>
  );
}

/** A page-shaped placeholder while the first query loads. */
export const PageSkeleton = () => (
  <>
    <div className="skeleton title" />
    <Card>
      <Skeleton />
    </Card>
    <Card>
      <Skeleton lines={4} />
    </Card>
  </>
);

export function Switch({
  checked,
  onChange,
  disabled,
  label,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className="switch"
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span />
    </button>
  );
}

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: ReactNode;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {error ? (
        <small className="field-error" role="alert">
          {error}
        </small>
      ) : (
        hint && <small className="field-hint">{hint}</small>
      )}
    </label>
  );
}

/** A submit/action button that shows a spinner and disables itself while pending. */
export function Button({
  pending,
  icon,
  variant = "primary",
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  pending?: boolean;
  icon?: IconName;
  variant?: "primary" | "secondary" | "ghost" | "danger";
}) {
  return (
    <button
      {...props}
      className={`btn ${variant}`}
      disabled={props.disabled || pending}
    >
      {pending ? <Spinner /> : icon && <Icon name={icon} />}
      {children}
    </button>
  );
}

export const shortSha = (sha: string) => sha.slice(0, 8);
