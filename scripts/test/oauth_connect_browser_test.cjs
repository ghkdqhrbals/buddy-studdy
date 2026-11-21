// Local UI fixture only: this does not verify SMTP, OAuth token exchange or ChatGPT.
// Run with Playwright resolvable: node --test scripts/test/oauth_connect_browser_test.cjs
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

const staticRoot = path.resolve(__dirname, '../../backend/tutor/src/main/resources/static');
const requestId = 'A'.repeat(43);
let browser, server, baseUrl;
let calls = [];
let expiredSession = false;

before(async () => {
  server = http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost');
    const json = (body, status = 200) => {
      response.writeHead(status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(body));
    };
    if (request.method === 'POST' || url.pathname === '/oauth/login-options') {
      let raw = '';
      for await (const chunk of request) raw += chunk;
      const body = raw ? JSON.parse(raw) : {};
      calls.push({ path: url.pathname, body });
      switch (url.pathname) {
        case '/oauth/login-options': return json({ providers: ['google', 'apple'] });
        case '/oauth/session': return expiredSession ? json({ error: 'invalid_request' }, 400) : json({ scopes: ['learning:read', 'profile:read'] });
        case '/oauth/login-state': return json({ verified: false, managementOnly: false });
        case '/oauth/password': return json({ verified: body.email === 'reviewer@example.test' && body.password === 'fixture-only-password' });
        case '/oauth/email-code': return json({ sent: true });
        case '/oauth/verify': return json({ verified: body.code === '12345678' });
        case '/oauth/terms': return json([]);
        default: return json({ error: 'unexpected_fixture_request' }, 404);
      }
    }
    if (url.pathname === '/favicon.ico') { response.writeHead(204); response.end(); return; }
    const relative = url.pathname.replace(/^\//, '');
    if (!relative.startsWith('oauth/') || relative.includes('..')) { response.writeHead(404); response.end(); return; }
    try {
      const content = await fs.readFile(path.join(staticRoot, relative));
      const extension = path.extname(relative);
      response.writeHead(200, { 'Content-Type': ({ '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' })[extension] || 'application/octet-stream' });
      response.end(content);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ channel: 'chrome', headless: true });
});

after(async () => {
  await browser?.close();
  if (server) await new Promise(resolve => server.close(resolve));
});

async function openPage(query = `?id=${requestId}`, options = {}) {
  calls = [];
  expiredSession = !!options.expired;
  const context = await browser.newContext({ locale: options.locale || 'en-US', viewport: options.viewport || { width: 1000, height: 850 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', entry => { if (entry.type() === 'error') errors.push(entry.text()); });
  await page.goto(`${baseUrl}/oauth/connect.html${query}`);
  await page.waitForFunction(() => document.querySelector('.page').dataset.stage === 'entry' || !document.getElementById('login-password').disabled);
  assert.equal(await page.title(), 'BuddyStudy 계정 연결');
  return { context, page, errors };
}

async function capture(page, name) {
  if (!process.env.OAUTH_BROWSER_EVIDENCE_DIR) return;
  await fs.mkdir(process.env.OAUTH_BROWSER_EVIDENCE_DIR, { recursive: true });
  await page.screenshot({ path: path.join(process.env.OAUTH_BROWSER_EVIDENCE_DIR, `${name}.png`), fullPage: true });
}

test('password entry signs in without requesting an email code and keeps consent explicit', async () => {
  const { context, page, errors } = await openPage();
  try {
    assert.equal(await page.getByRole('button', { name: 'Sign in with a password', exact: true }).isVisible(), true);
    assert.equal(await page.getByRole('button', { name: 'Sign in with an email code', exact: true }).isVisible(), true);
    assert.equal(await page.getByRole('button', { name: 'Sign in with Google', exact: true }).isVisible(), true);
    assert.equal(await page.getByRole('button', { name: 'Sign in with Apple', exact: true }).isVisible(), true);
    await capture(page, 'password-chooser-desktop');
    await page.getByRole('button', { name: 'Sign in with a password', exact: true }).click();
    assert.equal(await page.locator('#password').isVisible(), true);
    assert.equal(await page.locator('#password').getAttribute('required'), '');
    await page.getByLabel('Account email', { exact: true }).fill('reviewer@example.test');
    await page.getByLabel('Password', { exact: true }).fill('fixture-only-password');
    await capture(page, 'password-form-desktop');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.locator('#consent').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#password').inputValue(), '');
    assert.equal(calls.filter(call => ['/oauth/email-code', '/oauth/verify', '/oauth/consent'].includes(call.path)).length, 0);
    const passwordCalls = calls.filter(call => call.path === '/oauth/password');
    assert.equal(passwordCalls.length, 1);
    assert.equal(passwordCalls[0].body.id, requestId);
    assert.equal(passwordCalls[0].body.password, 'fixture-only-password');
    assert.deepEqual(errors, []);
  } finally { await context.close(); }
});

test('email-code entry still works and switching methods clears the password', async () => {
  const { context, page, errors } = await openPage();
  try {
    await page.locator('#login-password').click();
    await page.locator('#password').fill('do-not-retain');
    await page.getByRole('button', { name: 'Sign in with a code', exact: true }).click();
    assert.equal(await page.locator('#password').isVisible(), false);
    assert.equal(await page.locator('#password').inputValue(), '');
    assert.equal(await page.locator('#password').getAttribute('required'), null);
    await page.locator('#back-to-methods').click();
    await page.getByRole('button', { name: 'Sign in with an email code', exact: true }).click();
    await page.getByLabel('Account email', { exact: true }).fill('reviewer@example.test');
    await page.getByRole('button', { name: 'Send verification code', exact: true }).click();
    await page.locator('#code-form').waitFor({ state: 'visible' });
    await page.getByLabel('8-digit verification code', { exact: true }).fill('12345678');
    await page.getByRole('button', { name: 'Verify', exact: true }).click();
    await page.locator('#consent').waitFor({ state: 'visible' });
    assert.equal(calls.filter(call => call.path === '/oauth/email-code').length, 1);
    assert.equal(calls.filter(call => call.path === '/oauth/password').length, 0);
    assert.deepEqual(errors, []);
  } finally { await context.close(); }
});

test('missing or malformed connection requests show recovery instead of dead login forms', async () => {
  for (const query of ['', '?id=invalid']) {
    const { context, page, errors } = await openPage(query);
    try {
      assert.equal(await page.locator('#login').isVisible(), false);
      assert.equal(await page.getByRole('heading', { name: 'Start a new sign-in' }).isVisible(), true);
      assert.match(await page.locator('#screen-description').innerText(), /In ChatGPT, select Connect for BuddyStudy/);
      assert.equal(await page.getByRole('link', { name: 'Manage connected apps' }).getAttribute('href'), '/oauth/manage');
      assert.equal(calls.length, 0);
      assert.deepEqual(errors, []);
      if (!query) await capture(page, 'missing-connection-desktop');
    } finally { await context.close(); }
  }
});

test('expired connection requests hide login and offer a fresh entry', async () => {
  const { context, page } = await openPage(`?id=${requestId}`, { expired: true });
  try {
    await page.locator('#connection-entry').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#login').isVisible(), false);
    assert.match(await page.locator('#message').innerText(), /This request expired/);
    assert.equal(calls.filter(call => ['/oauth/password', '/oauth/email-code'].includes(call.path)).length, 0);
  } finally { await context.close(); }
});

test('Korean mobile chooser and password form remain readable and localized', async () => {
  const { context, page, errors } = await openPage(`?id=${requestId}`, { locale: 'ko-KR', viewport: { width: 390, height: 844 } });
  try {
    await capture(page, 'password-chooser-mobile-ko');
    await page.getByRole('button', { name: '비밀번호로 로그인', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '로그인', exact: true }).isVisible(), true);
    assert.equal(await page.getByRole('heading', { name: '이메일·비밀번호 로그인', exact: true }).isVisible(), true);
    assert.equal(await page.getByLabel('비밀번호', { exact: true }).isVisible(), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await capture(page, 'password-form-mobile-ko');
    await page.getByRole('button', { name: 'English', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: 'Sign in', exact: true }).isVisible(), true);
    assert.equal(await page.getByRole('heading', { name: 'Email and password', exact: true }).isVisible(), true);
    assert.deepEqual(errors, []);
  } finally { await context.close(); }
});
