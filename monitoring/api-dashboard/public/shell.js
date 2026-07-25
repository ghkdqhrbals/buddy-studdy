const NAV_COLLAPSED_KEY = "buddystudy.monitoring.nav.collapsed";
const NAV_GROUP_KEY = "buddystudy.monitoring.nav.groups";
const NAV_MODE_KEY = "buddystudy.monitoring.nav.mode";
const UI_VERSION = "2026.07.25.7";
const root = document.documentElement;

const groups = [
  {
    id: "observe",
    label: "Observe",
    items: [
      { href: "/", label: "API Logs", icon: "logs" },
      { href: "/performance.html", label: "API Performance", icon: "performance" },
      { href: "/system.html", label: "Server Dashboard", icon: "server" },
      { href: "/audit.html", label: "Access & Audit", icon: "audit" },
    ],
  },
  {
    id: "load-testing",
    label: "Load testing",
    items: [
      { href: "/testzone.html", label: "TestZone", icon: "rocket" },
    ],
  },
  {
    id: "tools",
    label: "Tools",
    items: [
      {
        href: "https://grafana.lowfidev.cloud/",
        label: "Grafana",
        icon: "external",
        external: true,
      },
      { href: "/settings.html", label: "Settings", icon: "settings" },
    ],
  },
];

const iconPaths = {
  menu: [
    '<path d="M4 6h16"/>',
    '<path d="M4 12h16"/>',
    '<path d="M4 18h16"/>',
  ],
  logs: [
    '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>',
    '<path d="M14 2v6h6"/>',
    '<path d="M8 13h8M8 17h6"/>',
  ],
  performance: [
    '<path d="M3 3v18h18"/>',
    '<path d="m7 16 4-5 4 3 5-7"/>',
  ],
  server: [
    '<rect width="20" height="8" x="2" y="3" rx="2"/>',
    '<rect width="20" height="8" x="2" y="13" rx="2"/>',
    '<path d="M6 7h.01M6 17h.01"/>',
  ],
  audit: [
    '<path d="M20 13c0 5-3.5 7.5-8 9-4.5-1.5-8-4-8-9V5l8-3 8 3z"/>',
    '<path d="m9 12 2 2 4-4"/>',
  ],
  rocket: [
    '<path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z"/>',
    '<path d="m12 15-3-3a22 22 0 0 1 2-3.95A12.3 12.3 0 0 1 22 2c0 2.72-.78 7.5-6.05 11a22.4 22.4 0 0 1-3.95 2z"/>',
    '<path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5"/>',
  ],
  external: [
    '<path d="M15 3h6v6"/>',
    '<path d="M10 14 21 3"/>',
    '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
  ],
  settings: [
    '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.38a2 2 0 0 0-.73-2.73l-.15-.09a2 2 0 0 1-1-1.74v-.51a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/>',
    '<circle cx="12" cy="12" r="3"/>',
  ],
  chevron: ['<path d="m6 9 6 6 6-6"/>'],
};

function createIcon(name, className = "side-nav-icon") {
  const wrapper = document.createElement("span");
  wrapper.className = className;
  wrapper.setAttribute("aria-hidden", "true");
  wrapper.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${iconPaths[name].join("")}</svg>`;
  return wrapper;
}

function readGroupState() {
  try {
    return JSON.parse(window.localStorage.getItem(NAV_GROUP_KEY) || "{}");
  } catch {
    return {};
  }
}

function isCurrentPage(href) {
  if (href.startsWith("http")) return false;
  const currentPath = window.location.pathname.replace(/\/+$/, "") || "/";
  const targetPath = href.replace(/\/+$/, "") || "/";
  return currentPath === targetPath;
}

function createGroup(group, savedState) {
  const details = document.createElement("details");
  details.className = "side-nav-group";
  details.dataset.group = group.id;
  details.open = savedState[group.id] !== false;

  const summary = document.createElement("summary");
  const label = document.createElement("span");
  label.textContent = group.label;
  const indicator = document.createElement("span");
  indicator.className = "side-nav-group-indicator";
  indicator.setAttribute("aria-hidden", "true");
  indicator.append(createIcon("chevron", "side-nav-chevron"));
  summary.append(label, indicator);

  const links = document.createElement("div");
  links.className = "side-nav-links";
  for (const item of group.items) {
    const link = document.createElement("a");
    link.href = item.href;
    link.title = item.label;
    link.dataset.label = item.label;
    const linkLabel = document.createElement("span");
    linkLabel.className = "side-nav-link-label";
    linkLabel.textContent = item.label;
    link.append(createIcon(item.icon), linkLabel);
    if (item.external) {
      link.target = "_blank";
      link.rel = "noreferrer";
    }
    if (isCurrentPage(item.href)) link.setAttribute("aria-current", "page");
    links.append(link);
  }

  details.append(summary, links);
  details.addEventListener("toggle", () => {
    if (root.classList.contains("nav-collapsed")) return;
    const nextState = readGroupState();
    nextState[group.id] = details.open;
    window.localStorage.setItem(NAV_GROUP_KEY, JSON.stringify(nextState));
  });
  return details;
}

function setCollapsed(collapsed) {
  const groupElements = [...document.querySelectorAll(".side-nav-group")];
  if (collapsed) {
    root.classList.add("nav-collapsed");
    for (const group of groupElements) {
      if (group.dataset.expandedOpen == null) {
        group.dataset.expandedOpen = String(group.open);
      }
      group.open = true;
    }
  } else {
    for (const group of groupElements) {
      if (group.dataset.expandedOpen != null) {
        group.open = group.dataset.expandedOpen === "true";
        delete group.dataset.expandedOpen;
      }
    }
    root.classList.remove("nav-collapsed");
  }
  window.localStorage.setItem(NAV_COLLAPSED_KEY, String(collapsed));
  const toggle = document.querySelector(".side-nav-toggle");
  toggle?.setAttribute("aria-expanded", String(!collapsed));
  toggle?.setAttribute("aria-label", collapsed ? "Expand navigation" : "Collapse navigation");
  toggle?.setAttribute("title", collapsed ? "Expand navigation" : "Collapse navigation");
}

function buildNavigation() {
  const aside = document.querySelector(".side-nav");
  if (!aside) return;

  const brand = document.createElement("div");
  brand.className = "side-nav-brand";
  const brandCopy = document.createElement("div");
  brandCopy.className = "side-nav-brand-copy";
  brandCopy.innerHTML = "<span>BuddyStudy</span><strong>Monitoring</strong>";
  const toggle = document.createElement("button");
  toggle.className = "side-nav-toggle";
  toggle.type = "button";
  toggle.title = "Collapse navigation";
  toggle.setAttribute("aria-label", "Collapse navigation");
  toggle.setAttribute("aria-expanded", "true");
  toggle.append(createIcon("menu", "side-nav-menu-icon"));
  toggle.addEventListener("click", () => {
    setCollapsed(!root.classList.contains("nav-collapsed"));
  });
  brand.append(toggle, brandCopy);

  const nav = document.createElement("nav");
  nav.setAttribute("aria-label", "Monitoring sections");
  const savedState = readGroupState();
  nav.append(...groups.map((group) => createGroup(group, savedState)));

  const footer = document.createElement("footer");
  footer.className = "side-nav-footer";
  footer.innerHTML = `<span>Monitoring UI</span><strong>v${UI_VERSION}</strong>`;

  aside.replaceChildren(brand, nav, footer);

  document.querySelector(".side-nav-reopen")?.remove();

  const navMode = window.localStorage.getItem(NAV_MODE_KEY) || "remember";
  const initiallyCollapsed = navMode === "compact"
    || (navMode === "remember" && window.localStorage.getItem(NAV_COLLAPSED_KEY) === "true");
  setCollapsed(initiallyCollapsed);

  requestAnimationFrame(() => {
    requestAnimationFrame(() => root.classList.add("nav-motion-ready"));
  });
}

window.addEventListener("monitoring:nav-mode-change", (event) => {
  const mode = event.detail?.mode;
  if (mode === "compact") setCollapsed(true);
  if (mode === "expanded") setCollapsed(false);
});

buildNavigation();
