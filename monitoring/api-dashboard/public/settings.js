const keys = {
  navMode: "buddystudy.monitoring.nav.mode",
  auditRange: "buddystudy.monitoring.audit.range",
  auditRefresh: "buddystudy.monitoring.audit.refreshSeconds",
  auditPageSize: "buddystudy.monitoring.audit.pageSize",
};

const defaults = {
  navMode: "remember",
  auditRange: "3600000",
  auditRefresh: "0",
  auditPageSize: "50",
};

const form = document.querySelector("#monitoringSettingsForm");
const status = document.querySelector("#settingsStatus");
const fields = {
  navMode: document.querySelector("#navigationMode"),
  auditRange: document.querySelector("#auditDefaultRange"),
  auditRefresh: document.querySelector("#auditRefreshSeconds"),
  auditPageSize: document.querySelector("#auditPageSize"),
};

function readSettings() {
  for (const [name, field] of Object.entries(fields)) {
    const value = window.localStorage.getItem(keys[name]) || defaults[name];
    field.value = [...field.options].some((option) => option.value === value)
      ? value
      : defaults[name];
  }
}

function saveSettings() {
  for (const [name, field] of Object.entries(fields)) {
    window.localStorage.setItem(keys[name], field.value);
  }
  window.dispatchEvent(new CustomEvent("monitoring:nav-mode-change", {
    detail: { mode: fields.navMode.value },
  }));
  status.textContent = "Settings saved";
  status.dataset.tone = "ready";
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  saveSettings();
});

document.querySelector("#resetSettingsButton").addEventListener("click", () => {
  for (const key of Object.values(keys)) window.localStorage.removeItem(key);
  readSettings();
  window.dispatchEvent(new CustomEvent("monitoring:nav-mode-change", {
    detail: { mode: defaults.navMode },
  }));
  status.textContent = "Defaults restored";
  status.dataset.tone = "ready";
});

readSettings();
