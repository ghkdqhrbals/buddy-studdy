const NAV_COLLAPSED_KEY = "buddystudy.monitoring.nav.collapsed";
const NAV_GROUP_KEY = "buddystudy.monitoring.nav.groups";
const UI_VERSION = "2026.07.25.4";

const groups = [
  {
    id: "observe",
    label: "Observe",
    items: [
      { href: "/", label: "API Logs" },
      { href: "/performance.html", label: "API Performance" },
      { href: "/system.html", label: "Server Dashboard" },
      { href: "/audit.html", label: "Access & Audit" },
    ],
  },
  {
    id: "load-testing",
    label: "Load testing",
    items: [
      { href: "/testzone.html", label: "TestZone" },
    ],
  },
  {
    id: "tools",
    label: "Tools",
    items: [
      {
        href: "https://grafana.lowfidev.cloud/",
        label: "Grafana",
        external: true,
      },
    ],
  },
];

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
  indicator.textContent = "⌄";
  summary.append(label, indicator);

  const links = document.createElement("div");
  links.className = "side-nav-links";
  for (const item of group.items) {
    const link = document.createElement("a");
    link.href = item.href;
    link.textContent = item.label;
    link.title = item.label;
    if (item.external) {
      link.target = "_blank";
      link.rel = "noreferrer";
    }
    if (isCurrentPage(item.href)) link.setAttribute("aria-current", "page");
    links.append(link);
  }

  details.append(summary, links);
  details.addEventListener("toggle", () => {
    const nextState = readGroupState();
    nextState[group.id] = details.open;
    window.localStorage.setItem(NAV_GROUP_KEY, JSON.stringify(nextState));
  });
  return details;
}

function setCollapsed(collapsed) {
  document.body.classList.toggle("nav-collapsed", collapsed);
  window.localStorage.setItem(NAV_COLLAPSED_KEY, String(collapsed));
  const toggle = document.querySelector(".side-nav-toggle");
  toggle?.setAttribute("aria-expanded", String(!collapsed));
  toggle?.setAttribute("title", collapsed ? "Open navigation" : "Close navigation");
}

function buildNavigation() {
  const aside = document.querySelector(".side-nav");
  if (!aside) return;

  const brand = document.createElement("div");
  brand.className = "side-nav-brand";
  const brandCopy = document.createElement("div");
  brandCopy.innerHTML = "<span>BuddyStudy</span><strong>Monitoring</strong>";
  const toggle = document.createElement("button");
  toggle.className = "side-nav-toggle";
  toggle.type = "button";
  toggle.title = "Close navigation";
  toggle.setAttribute("aria-label", "Close navigation");
  toggle.setAttribute("aria-expanded", "true");
  toggle.textContent = "×";
  toggle.addEventListener("click", () => setCollapsed(true));
  brand.append(brandCopy, toggle);

  const nav = document.createElement("nav");
  nav.setAttribute("aria-label", "Monitoring sections");
  const savedState = readGroupState();
  nav.append(...groups.map((group) => createGroup(group, savedState)));

  const footer = document.createElement("footer");
  footer.className = "side-nav-footer";
  footer.innerHTML = `<span>Monitoring UI</span><strong>v${UI_VERSION}</strong>`;

  aside.replaceChildren(brand, nav, footer);

  let reopen = document.querySelector(".side-nav-reopen");
  if (!reopen) {
    reopen = document.createElement("button");
    reopen.className = "side-nav-reopen";
    reopen.type = "button";
    reopen.title = "Open navigation";
    reopen.setAttribute("aria-label", "Open navigation");
    reopen.textContent = "☰";
    reopen.addEventListener("click", () => setCollapsed(false));
    document.body.append(reopen);
  }

  setCollapsed(window.localStorage.getItem(NAV_COLLAPSED_KEY) === "true");
}

buildNavigation();
