import { RotateCcw, Save } from "lucide-react";
import { useState } from "react";
import { PageHeader } from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";

const settings = {
  navMode: ["buddystudy.monitoring.nav.mode", "remember"],
  auditRange: ["buddystudy.monitoring.audit.range", "3600000"],
  auditRefresh: ["buddystudy.monitoring.audit.refreshSeconds", "0"],
  auditPageSize: ["buddystudy.monitoring.audit.pageSize", "50"],
};

function readValues() {
  return Object.fromEntries(Object.entries(settings).map(([name, [key, fallback]]) => [
    name,
    window.localStorage.getItem(key) || fallback,
  ]));
}

export function SettingsPage() {
  const [values, setValues] = useState(readValues);
  const [message, setMessage] = useState("");
  function update(name, value) {
    setValues((current) => ({ ...current, [name]: value }));
    setMessage("");
  }
  function save(event) {
    event.preventDefault();
    for (const [name, [key]] of Object.entries(settings)) window.localStorage.setItem(key, values[name]);
    window.dispatchEvent(new CustomEvent("monitoring:nav-mode-change", { detail: { mode: values.navMode } }));
    setMessage("Settings saved for this browser.");
  }
  function reset() {
    for (const [key] of Object.values(settings)) window.localStorage.removeItem(key);
    setValues(readValues());
    window.dispatchEvent(new CustomEvent("monitoring:nav-mode-change", { detail: { mode: "remember" } }));
    setMessage("Default settings restored.");
  }
  return (
    <>
      <PageHeader eyebrow="Tools" title="Settings" description="Configure this monitoring browser without changing backend runtime settings." />
      <section className="workspace-section settings-workspace">
        <form onSubmit={save}>
          <div className="settings-group">
            <div><h2>Navigation</h2><p>Choose how the fixed navigation behaves when this console opens.</p></div>
            <label className="field"><span>Default navigation</span><select value={values.navMode} onChange={(event) => update("navMode", event.target.value)}><option value="remember">Remember last state</option><option value="expanded">Always expanded</option><option value="compact">Always compact</option></select></label>
          </div>
          <div className="settings-group">
            <div><h2>Access & audit</h2><p>Control the default audit window, refresh cadence, and table density.</p></div>
            <div className="form-grid three-columns">
              <label className="field"><span>Default range</span><select value={values.auditRange} onChange={(event) => update("auditRange", event.target.value)}><option value="3600000">Last hour</option><option value="21600000">Last 6 hours</option><option value="86400000">Last 24 hours</option><option value="604800000">Last 7 days</option></select></label>
              <label className="field"><span>Auto refresh</span><select value={values.auditRefresh} onChange={(event) => update("auditRefresh", event.target.value)}><option value="0">Off</option><option value="10">10 seconds</option><option value="30">30 seconds</option><option value="60">1 minute</option></select></label>
              <label className="field"><span>Rows per page</span><select value={values.auditPageSize} onChange={(event) => update("auditPageSize", event.target.value)}><option value="20">20 rows</option><option value="50">50 rows</option><option value="100">100 rows</option></select></label>
            </div>
          </div>
          <div className="settings-actions">
            {message ? <InlineNotice tone="success" compact>{message}</InlineNotice> : <span />}
            <Button type="button" variant="ghost" icon={RotateCcw} onClick={reset}>Restore defaults</Button>
            <Button type="submit" icon={Save}>Save settings</Button>
          </div>
        </form>
      </section>
    </>
  );
}
