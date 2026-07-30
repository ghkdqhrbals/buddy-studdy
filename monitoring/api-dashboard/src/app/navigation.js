import {
  Activity,
  BookOpenText,
  CalendarClock,
  ExternalLink,
  Gauge,
  GitPullRequest,
  History,
  MessageSquareText,
  Wrench,
  Rocket,
  Server,
  Settings,
  ShieldCheck,
  Users,
} from "lucide-react";

export const UI_VERSION = "2026.07.30.4";
export const NAV_COLLAPSED_KEY = "buddystudy.monitoring.nav.collapsed";
export const NAV_GROUP_KEY = "buddystudy.monitoring.nav.groups";
export const NAV_MODE_KEY = "buddystudy.monitoring.nav.mode";

const serverDashboard = "https://grafana.lowfidev.cloud/d/buddystudy-server-runtime/buddystudy-server-dashboard?orgId=1&from=now-1h&to=now&timezone=browser&refresh=10s";

export const navigationGroups = [
  {
    id: "observe",
    label: "Observe",
    items: [
      { href: "/", label: "API Logs", icon: BookOpenText },
      { href: "/performance.html", label: "API Performance", icon: Activity },
      { href: serverDashboard, label: "Server Dashboard", icon: Server, external: true },
      { href: "/audit.html", label: "Access & Audit", icon: ShieldCheck },
    ],
  },
  {
    id: "manage",
    label: "Manage",
    items: [
      { href: "/users.html", label: "Users & Quotas", icon: Users },
      { href: "/feedback.html", label: "User Feedback", icon: MessageSquareText },
      { href: "/jobs.html", label: "Batch Jobs", icon: CalendarClock },
      { href: "/streams.html", label: "Redis Streams", icon: GitPullRequest },
      { href: "/service-status.html", label: "App Control", icon: Wrench },
    ],
  },
  {
    id: "load-testing",
    label: "Load testing",
    items: [
      { href: "/testzone.html", label: "TestZone", icon: Rocket },
    ],
  },
  {
    id: "tools",
    label: "Tools",
    items: [
      { href: "https://grafana.lowfidev.cloud/", label: "Grafana", icon: Gauge, external: true },
      { href: "/settings.html", label: "Settings", icon: Settings },
    ],
  },
];

export const utilityIcons = {
  external: ExternalLink,
  history: History,
};
