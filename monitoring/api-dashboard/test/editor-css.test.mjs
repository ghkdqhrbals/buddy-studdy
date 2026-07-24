import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const css = await readFile(new URL("../public/testzone.css", import.meta.url), "utf8");
const html = await readFile(new URL("../public/testzone.html", import.meta.url), "utf8");
const javascript = await readFile(new URL("../public/testzone.js", import.meta.url), "utf8");

test("script editor hides native glyphs behind the syntax highlight layer", () => {
  const editorRule = css.match(/\.code-editor #scriptEditor \{(?<body>[^}]+)\}/)?.groups?.body ?? "";
  assert.match(editorRule, /color:\s*transparent;/);
  assert.match(editorRule, /-webkit-text-fill-color:\s*transparent;/);
  assert.match(editorRule, /text-shadow:\s*none;/);

  const highlightRule = css.match(/\.script-highlight \{(?<body>[^}]+)\}/)?.groups?.body ?? "";
  assert.match(highlightRule, /color:\s*#dce6f4;/);
  assert.match(highlightRule, /pointer-events:\s*none;/);
  assert.match(highlightRule, /align-self:\s*stretch;/);
  assert.match(highlightRule, /max-height:\s*none;/);
  assert.doesNotMatch(highlightRule, /height:\s*100%;/);
});

test("script workspace contains only files, editor, and Run Plan controls", () => {
  for (const source of [html, css, javascript]) {
    assert.doesNotMatch(source, /assistant/i);
  }
  assert.match(html, /id="editorRunButton"/);
  assert.match(html, /id="runDialog"/);
});

test("run chart exposes hover details and keyboard navigation", () => {
  assert.match(html, /id="runChartTooltip"/);
  assert.match(html, /tabindex="0"/);
  assert.match(css, /\.run-chart-tooltip/);
  assert.match(javascript, /pointermove/);
  assert.match(javascript, /handleRunChartKeydown/);
});

test("selected history run renders its time-series inside the detail panel", () => {
  const detail = html.match(/<section id="runDetail"[\s\S]+?<\/section>\s*<\/section>/)?.[0] ?? "";
  assert.match(detail, /id="runDetailChart"/);
  assert.match(detail, /id="runDetailChartTooltip"/);
  assert.match(detail, /id="runDetailChartEmpty"/);
  assert.match(css, /\.run-detail-timeline/);
  assert.match(javascript, /runDetailChartHoverIndex/);
  assert.match(
    javascript,
    /drawRunChartCanvas\(\s*elements\.runDetailChart,\s*elements\.runDetailChartTooltip,\s*elements\.runDetailChartEmpty/,
  );
});
