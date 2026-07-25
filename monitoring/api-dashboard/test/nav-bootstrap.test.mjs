import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(
  new URL("../public/nav-bootstrap.js", import.meta.url),
  "utf8",
);

function restoreNavigationClass(values = {}, throws = false) {
  const classes = new Set();
  const classList = {
    add: (name) => classes.add(name),
    remove: (name) => classes.delete(name),
    toggle: (name, enabled) => {
      if (enabled) classes.add(name);
      else classes.delete(name);
    },
  };
  const localStorage = {
    getItem: (key) => {
      if (throws) throw new Error("storage unavailable");
      return values[key] ?? null;
    },
  };

  vm.runInNewContext(source, {
    document: { documentElement: { classList } },
    window: { localStorage },
  });
  return classes;
}

test("navigation bootstrap restores compact state before the shell renders", () => {
  assert.equal(
    restoreNavigationClass({
      "buddystudy.monitoring.nav.mode": "compact",
    }).has("nav-collapsed"),
    true,
  );
  assert.equal(
    restoreNavigationClass({
      "buddystudy.monitoring.nav.mode": "remember",
      "buddystudy.monitoring.nav.collapsed": "true",
    }).has("nav-collapsed"),
    true,
  );
});

test("navigation bootstrap keeps expanded mode open and fails open safely", () => {
  assert.equal(
    restoreNavigationClass({
      "buddystudy.monitoring.nav.mode": "expanded",
      "buddystudy.monitoring.nav.collapsed": "true",
    }).has("nav-collapsed"),
    false,
  );
  assert.equal(restoreNavigationClass({}, true).has("nav-collapsed"), false);
});
