import { Activity, ChevronDown, Menu, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import {
  NAV_COLLAPSED_KEY,
  NAV_GROUP_KEY,
  NAV_MODE_KEY,
  navigationGroups,
  UI_VERSION,
} from "./navigation.js";

function storedJson(key, fallback) {
  try {
    return JSON.parse(window.localStorage.getItem(key) || "") ?? fallback;
  } catch {
    return fallback;
  }
}

function initialCollapsed() {
  if (window.matchMedia("(max-width: 700px)").matches) return true;
  const mode = window.localStorage.getItem(NAV_MODE_KEY) || "remember";
  return mode === "compact"
    || (mode === "remember" && window.localStorage.getItem(NAV_COLLAPSED_KEY) === "true");
}

function NavItem({ item, collapsed, currentPath }) {
  const Icon = item.icon;
  const current = !item.external && currentPath === item.href;
  return (
    <a
      className="react-nav-link"
      data-current={current ? "true" : "false"}
      href={item.href}
      title={collapsed ? item.label : undefined}
      target={item.external ? "_blank" : undefined}
      rel={item.external ? "noreferrer" : undefined}
    >
      <Icon size={18} strokeWidth={1.8} aria-hidden="true" />
      <span>{item.label}</span>
    </a>
  );
}

export function AppShell({ children, contentClassName = "" }) {
  const [collapsed, setCollapsed] = useState(initialCollapsed);
  const [openGroups, setOpenGroups] = useState(() => storedJson(NAV_GROUP_KEY, {}));

  const currentPath = useMemo(() => window.location.pathname || "/", []);
  const compactVersion = useMemo(
    () => UI_VERSION.split(".").slice(-2).join("."),
    [],
  );

  useEffect(() => {
    document.documentElement.classList.toggle("nav-collapsed", collapsed);
    document.documentElement.classList.add("nav-motion-ready");
    window.localStorage.setItem(NAV_COLLAPSED_KEY, String(collapsed));
    return () => document.documentElement.classList.remove("nav-motion-ready");
  }, [collapsed]);

  useEffect(() => {
    const applyConfiguredMode = (event) => {
      const mode = event.detail?.mode;
      if (mode === "compact") setCollapsed(true);
      if (mode === "expanded") setCollapsed(false);
    };
    window.addEventListener("monitoring:nav-mode-change", applyConfiguredMode);
    return () => window.removeEventListener("monitoring:nav-mode-change", applyConfiguredMode);
  }, []);

  function toggleGroup(groupId) {
    if (collapsed) return;
    setOpenGroups((current) => {
      const next = { ...current, [groupId]: current[groupId] === false };
      window.localStorage.setItem(NAV_GROUP_KEY, JSON.stringify(next));
      return next;
    });
  }

  return (
    <div className="react-app-shell">
      <aside className="react-side-nav" data-collapsed={collapsed ? "true" : "false"}>
        <div className="react-nav-brand">
          <a className="react-brand-home" href="/" aria-label="BuddyStudy Monitoring home">
            <span className="react-brand-mark">
              <Activity size={17} strokeWidth={2} aria-hidden="true" />
            </span>
            <span className="react-brand-copy">
              <strong>BuddyStudy</strong>
              <span>Monitoring</span>
            </span>
          </a>
          <button
            className="icon-button nav-toggle"
            type="button"
            onClick={() => setCollapsed((value) => !value)}
            aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}
            title={collapsed ? "Expand navigation" : "Collapse navigation"}
          >
            {collapsed ? <PanelLeftOpen size={19} /> : <PanelLeftClose size={19} />}
          </button>
        </div>

        <nav className="react-navigation" aria-label="Monitoring sections">
          {navigationGroups.map((group) => {
            const isOpen = collapsed || openGroups[group.id] !== false;
            return (
              <section className="react-nav-group" key={group.id}>
                <button
                  className="react-nav-group-button"
                  type="button"
                  onClick={() => toggleGroup(group.id)}
                  aria-expanded={isOpen}
                  tabIndex={collapsed ? -1 : 0}
                >
                  <span>{group.label}</span>
                  <ChevronDown size={14} aria-hidden="true" />
                </button>
                {isOpen ? (
                  <div className="react-nav-items">
                    {group.items.map((item) => (
                      <NavItem
                        key={item.href}
                        item={item}
                        collapsed={collapsed}
                        currentPath={currentPath}
                      />
                    ))}
                  </div>
                ) : null}
              </section>
            );
          })}
        </nav>

        <footer className="react-nav-footer">
          <span>Status console</span>
          <strong className="version-full">v{UI_VERSION}</strong>
          <strong className="version-compact" title={`Monitoring UI v${UI_VERSION}`}>
            v{compactVersion}
          </strong>
        </footer>
      </aside>

      <button
        className="react-nav-backdrop"
        type="button"
        data-visible={collapsed ? "false" : "true"}
        onClick={() => setCollapsed(true)}
        aria-label="Close navigation"
        tabIndex={collapsed ? -1 : 0}
      />

      <main
        className={`react-content ${contentClassName}`.trim()}
        data-nav-collapsed={collapsed ? "true" : "false"}
      >
        <button
          className="mobile-nav-button icon-button"
          type="button"
          onClick={() => setCollapsed((value) => !value)}
          aria-label="Toggle navigation"
        >
          <Menu size={20} />
        </button>
        {children}
      </main>
    </div>
  );
}
