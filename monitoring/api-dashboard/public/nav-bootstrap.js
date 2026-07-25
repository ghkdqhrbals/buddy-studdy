(() => {
  const collapsedKey = "buddystudy.monitoring.nav.collapsed";
  const modeKey = "buddystudy.monitoring.nav.mode";

  try {
    const mode = window.localStorage.getItem(modeKey) || "remember";
    const collapsed = mode === "compact"
      || (mode === "remember" && window.localStorage.getItem(collapsedKey) === "true");
    document.documentElement.classList.toggle("nav-collapsed", collapsed);
  } catch {
    document.documentElement.classList.remove("nav-collapsed");
  }
})();
