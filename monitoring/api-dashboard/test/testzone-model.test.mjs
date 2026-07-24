import test from "node:test";
import assert from "node:assert/strict";
import {
  RUN_PROFILES,
  diagnosticMessage,
  editorPosition,
  formatMilliseconds,
  formatPercent,
  formatRate,
  highlightJavaScript,
  lineNumbersFor,
  parseObjectJson,
  runScriptName,
  selectLatestRun,
} from "../public/testzone-model.js";

const runs = [
  {
    id: "older",
    status: "completed",
    createdAt: "2026-07-24T01:00:00Z",
    summary: { requestRate: 800.2, p95Ms: 41.5, errorRate: 0.001 },
  },
  {
    id: "newer",
    status: "completed",
    createdAt: "2026-07-24T02:00:00Z",
    summary: { requestRate: 995.4, p95Ms: 57.1, errorRate: 0.002 },
  },
  {
    id: "running",
    status: "running",
    createdAt: "2026-07-24T03:00:00Z",
    summary: null,
  },
];

test("run profiles keep every preset within the 1,000 VU ceiling", () => {
  for (const profile of Object.values(RUN_PROFILES)) {
    assert.ok(profile.vus <= 1000);
    assert.ok(profile.maxVus <= 1000);
  }
});

test("JSON fields accept objects and reject invalid or array values", () => {
  assert.deepEqual(parseObjectJson('{"Authorization":"Bearer token"}', "Headers"), {
    Authorization: "Bearer token",
  });
  assert.throws(() => parseObjectJson("[1]", "Headers"), /JSON object/);
  assert.throws(() => parseObjectJson("{", "Headers"), /valid JSON/);
});

test("latest run uses execution timestamps", () => {
  assert.equal(selectLatestRun(runs).id, "running");
});

test("run history keeps the execution-time script name and supports legacy runs", () => {
  assert.equal(runScriptName({ scriptName: "saved-name.js", scriptId: "old" }, []), "saved-name.js");
  assert.equal(
    runScriptName({ scriptId: "legacy" }, [{ id: "legacy", name: "legacy-name.js" }]),
    "legacy-name.js",
  );
  assert.equal(runScriptName({ scriptId: "missing" }, []), "Deleted script");
});

test("formatters preserve missing values and display operational units", () => {
  assert.equal(formatRate(995.4), "995 RPS");
  assert.equal(formatMilliseconds(57.1), "57.1 ms");
  assert.equal(formatMilliseconds(1500), "1.50 s");
  assert.equal(formatPercent(0.0123), "1.23%");
  assert.equal(formatRate(null), "-");
});

test("editor helpers keep line numbering and caret position stable", () => {
  assert.equal(lineNumbersFor("one\ntwo\nthree"), "1\n2\n3");
  assert.deepEqual(editorPosition("one\ntwo", 6), { line: 2, column: 3 });
  assert.match(highlightJavaScript("const value = http.get(\"/api\");"), /syntax-keyword/);
  assert.match(highlightJavaScript("const value = http.get(\"/api\");"), /syntax-builtin/);
  assert.equal(
    diagnosticMessage({ line: 4, column: 12, message: "Use __ENV.BASE_URL." }),
    "Ln 4:12 Use __ENV.BASE_URL.",
  );
});
