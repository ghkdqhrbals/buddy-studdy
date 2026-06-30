import type { MouseEvent, ReactNode } from "react";
import { sections } from "./adminConfig";
import type { SectionKey, Theme } from "./types";

type AdminShellProps = {
  activeSection: SectionKey;
  activeLabel: string;
  startDate: string;
  endDate: string;
  theme: Theme;
  loading: boolean;
  error: string | null;
  hrefForSection: (section: SectionKey) => string;
  onDateRangeChange: (startDate: string, endDate: string) => void;
  onLogout: () => void;
  onNavigate: (section: SectionKey) => void;
  onRefresh: () => void;
  onThemeChange: (theme: Theme) => void;
  children: ReactNode;
};

export function AdminShell({
  activeSection,
  activeLabel,
  startDate,
  endDate,
  theme,
  loading,
  error,
  hrefForSection,
  onDateRangeChange,
  onLogout,
  onNavigate,
  onRefresh,
  onThemeChange,
  children,
}: AdminShellProps) {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="/home"
          title="Home"
          onClick={(event) => {
            if (!shouldHandleClientNavigation(event)) return;
            event.preventDefault();
            onNavigate("overview");
          }}
        >
          <div className="brand-mark">B</div>
          <div>
            <strong>BuddyStudy</strong>
            <span>Admin</span>
          </div>
        </a>
        <nav className="nav-list" aria-label="Primary navigation">
          {sections.map((section) => (
            <a
              key={section.key}
              href={hrefForSection(section.key)}
              className={section.key === activeSection ? "nav-item active" : "nav-item"}
              title={section.label}
              aria-current={section.key === activeSection ? "page" : undefined}
              onClick={(event) => {
                if (!shouldHandleClientNavigation(event)) return;
                event.preventDefault();
                onNavigate(section.key);
              }}
            >
              {section.label}
            </a>
          ))}
        </nav>
        <div className="sidebar-footer">
          <details className="account-menu">
            <summary className="admin-chip" title="Admin account">
              <span className="account-dot" aria-hidden="true" />
              <span>
                <strong>admin</strong>
                <small>Administrator</small>
              </span>
            </summary>
            <div className="account-popover">
              <button className="logout-button" title="Sign out" onClick={onLogout}>Sign out</button>
            </div>
          </details>
        </div>
      </aside>

      <main className="main" aria-busy={loading}>
        <header className="topbar">
          <div className="topbar-title">
            <h1>{activeLabel}</h1>
          </div>
          <div className="toolbar">
            {activeSection === "operations" ? null : (
              <DateRange
                startDate={startDate}
                endDate={endDate}
                setStartDate={(value) => onDateRangeChange(value, endDate)}
                setEndDate={(value) => onDateRangeChange(startDate, value)}
              />
            )}
            <button className="secondary-button icon-button square-button" aria-label="Refresh" title="Refresh" onClick={onRefresh} disabled={loading}>
              <Icon name="refresh" />
            </button>
            <button className="secondary-button icon-button square-button" aria-label="Toggle theme" title="Toggle theme" onClick={() => onThemeChange(theme === "light" ? "dark" : "light")}>
              <Icon name={theme === "light" ? "moon" : "sun"} />
            </button>
          </div>
        </header>

        {error ? <div className="error-banner" role="alert">{error}</div> : null}
        {loading ? <div className="loading-bar" /> : null}
        {children}
      </main>
    </div>
  );
}

function DateRange({
  startDate,
  endDate,
  setStartDate,
  setEndDate,
}: {
  startDate: string;
  endDate: string;
  setStartDate: (value: string) => void;
  setEndDate: (value: string) => void;
}) {
  return (
    <div className="date-range" role="group" aria-label="Metric date range">
      <input
        aria-label="Start date"
        type="date"
        value={startDate}
        onChange={(event) => setStartDate(event.target.value)}
      />
      <span aria-hidden="true">~</span>
      <input
        aria-label="End date"
        type="date"
        value={endDate}
        onChange={(event) => setEndDate(event.target.value)}
      />
    </div>
  );
}

function shouldHandleClientNavigation(event: MouseEvent<HTMLAnchorElement>): boolean {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}

export function Icon({ name }: { name: "refresh" | "moon" | "sun" }) {
  if (name === "refresh") {
    return (
      <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M15.4 6.2A6 6 0 1 0 16 10" />
        <path d="M15.5 3.8v3h-3" />
      </svg>
    );
  }
  if (name === "moon") {
    return (
      <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M14.7 13.7A6.7 6.7 0 0 1 6.3 5.3 6.9 6.9 0 1 0 14.7 13.7Z" />
      </svg>
    );
  }
  return (
    <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="10" cy="10" r="3.3" />
      <path d="M10 1.8v2M10 16.2v2M1.8 10h2M16.2 10h2M4.2 4.2l1.4 1.4M14.4 14.4l1.4 1.4M15.8 4.2l-1.4 1.4M5.6 14.4l-1.4 1.4" />
    </svg>
  );
}
