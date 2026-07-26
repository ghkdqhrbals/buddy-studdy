import { AlertTriangle, RefreshCw } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { PageHeader } from "../components/AdminUI.jsx";

const controllerLoads = new Map();
const EMPTY_TEMPLATE_SELECTOR = ["#emptyTemplate"];
const TESTZONE_EXTRA_SELECTORS = ["dialog", "#toastRegion"];

function pageMarkup(templateId, contentSelector, removeSelector, extraSelectors = []) {
  const template = document.querySelector(`#${templateId}`);
  if (!template) return "";
  const fragment = template.content.cloneNode(true);
  const staging = document.createElement("div");
  staging.append(fragment);
  const content = staging.querySelector(contentSelector);
  if (!content) return "";
  content.querySelector(removeSelector)?.remove();
  const extras = extraSelectors
    .flatMap((selector) => [...staging.querySelectorAll(selector)])
    .map((element) => element.outerHTML)
    .join("");
  return `${content.innerHTML}${extras}`;
}

function loadController(url) {
  if (!controllerLoads.has(url)) {
    controllerLoads.set(url, import(/* @vite-ignore */ url));
  }
  return controllerLoads.get(url);
}

function LegacyControllerSurface({
  templateId,
  contentSelector,
  removeSelector,
  extraSelectors,
  controllerUrl,
  className,
  children,
}) {
  const [error, setError] = useState("");
  const markup = useMemo(
    () => pageMarkup(templateId, contentSelector, removeSelector, extraSelectors),
    [contentSelector, extraSelectors, removeSelector, templateId],
  );

  useEffect(() => {
    let active = true;
    loadController(controllerUrl).catch((loadError) => {
      if (active) setError(loadError?.message || "The page controller could not be loaded.");
    });
    return () => {
      active = false;
    };
  }, [controllerUrl]);

  return (
    <div className={className}>
      {children}
      {error ? (
        <div className="legacy-controller-error" role="alert">
          <AlertTriangle size={17} />
          <span>{error}</span>
          <button type="button" onClick={() => window.location.reload()}>
            <RefreshCw size={15} /> Reload
          </button>
        </div>
      ) : null}
      <div className="react-legacy-surface" dangerouslySetInnerHTML={{ __html: markup }} />
    </div>
  );
}

export function ApiLogsPage() {
  useEffect(() => {
    document.title = "BuddyStudy API Logs";
  }, []);
  return (
    <LegacyControllerSurface
      templateId="api-logs-page-template"
      contentSelector=".content-shell"
      removeSelector=".topbar"
      extraSelectors={EMPTY_TEMPLATE_SELECTOR}
      controllerUrl="/app.js?v=2026072704"
      className="observe-page"
    >
      <PageHeader
        eyebrow="Observe"
        title="API logs"
        description="Search request traces, inspect payloads, and follow every related backend log by request ID."
      />
    </LegacyControllerSurface>
  );
}

export function ApiPerformancePage() {
  useEffect(() => {
    document.title = "BuddyStudy API Performance";
  }, []);
  return (
    <LegacyControllerSurface
      templateId="api-performance-page-template"
      contentSelector=".content-shell"
      removeSelector=".topbar"
      extraSelectors={EMPTY_TEMPLATE_SELECTOR}
      controllerUrl="/performance.js?v=2026072704"
      className="observe-page"
    >
      <PageHeader
        eyebrow="Observe"
        title="API performance"
        description="Compare request volume, errors, and latency percentiles for each API endpoint."
      />
    </LegacyControllerSurface>
  );
}

export function TestZonePage() {
  useEffect(() => {
    document.title = "BuddyStudy TestZone";
  }, []);
  return (
    <LegacyControllerSurface
      templateId="testzone-page-template"
      contentSelector=".testzone-content"
      removeSelector=".testzone-header"
      extraSelectors={TESTZONE_EXTRA_SELECTORS}
      controllerUrl="/testzone.js?v=2026072704"
      className="testzone-content testzone-react-page"
    >
      <header className="testzone-header">
        <div>
          <span className="testzone-eyebrow">Load testing</span>
          <h1>TestZone</h1>
          <p>Write k6 scripts, run isolated tests, and inspect multi-scenario results in one workspace.</p>
        </div>
        <div className="header-actions">
          <span id="serviceStatus" className="service-status" data-state="loading">Checking service</span>
        </div>
      </header>
    </LegacyControllerSurface>
  );
}
