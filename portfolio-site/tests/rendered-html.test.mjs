import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

const templateRoot = new URL("../", import.meta.url);

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders the BuddyStudy engineering document", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="ko">/i);
  assert.match(html, /<title>BuddyStudy \| AI 학습 시스템 포트폴리오<\/title>/i);
  assert.match(html, /무엇을 개선했는가/);
  assert.match(html, /Engineering Notes/);
  assert.match(html, /On this page/);
  assert.match(html, /문제/);
  assert.match(html, /개선/);
  assert.match(html, /결과/);
  assert.match(html, /href="#performance"/);
  assert.match(html, /href="#security"/);
  assert.match(html, /3,000/);
  assert.match(html, /97\.9%/);
  assert.match(html, /780\.94/);
  assert.match(html, /16\.53/);
  assert.match(html, /WARP/);
  assert.match(html, /Native Image/);
  assert.match(html, /load-test-dashboard\.png/);
  assert.match(html, /study\.png/);
  assert.doesNotMatch(html, /Your site is taking shape|Building your site/);
});

test("keeps the evidence and public metadata in source control", async () => {
  const [css, page, layout, guide] = await Promise.all([
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../../docs/PORTFOLIO_INTERVIEW_GUIDE.md", import.meta.url), "utf8"),
  ]);

  assert.match(page, /id="product"/);
  assert.match(page, /id="architecture"/);
  assert.match(page, /id="testing"/);
  assert.match(page, /id="improvements"/);
  assert.match(page, /무엇을 개선했는가/);
  assert.match(layout, /https:\/\/buddystudy\.lowfidev\.cloud/);
  assert.match(layout, /\/og\.png/);
  assert.match(css, /@media \(max-width: 640px\)/);
  assert.match(css, /prefers-reduced-motion/);
  assert.match(css, /\.markdown-body/);
  assert.doesNotMatch(css, /\.hero-phones/);
  assert.match(guide, /30-Second Explanation/);
  assert.match(guide, /MVC\/JDBC and WebFlux\/R2DBC/);

  await assert.rejects(
    access(new URL("app/_sites-preview/SkeletonPreview.tsx", templateRoot)),
  );
});
