'use strict';
const id = new URLSearchParams(location.search).get('id');
const manage = new URLSearchParams(location.search).get('manage') === '1';
let language = navigator.language.startsWith('ko') ? 'ko' : 'en';
let stage = 'email';
let activeConnections = null;
let grantedScopes = new Set();
const message = document.getElementById('message');
const emailInput = document.getElementById('email');
const text = (ko, en) => language === 'ko' ? ko : en;
function translate() {
  document.documentElement.lang = language;
  const prefix = language === 'ko' ? '' : 'en/';
  document.getElementById('connection-notice').href = `https://ghkdqhrbals.github.io/buddy-studdy/${prefix}${grantedScopes.has('learning:write') || grantedScopes.has('learning:delete') ? 'chatgpt-learning-management-2026-10-21.html' : 'chatgpt-connection-2026-10-20.html'}`;
  document.getElementById('privacy').href = `https://ghkdqhrbals.github.io/buddy-studdy/${prefix}privacy.html`;
  document.querySelectorAll('[data-ko]').forEach(node => { node.textContent = node.dataset[language]; });
  document.querySelectorAll('[data-scope]').forEach(node => { node.hidden = !grantedScopes.has(node.dataset.scope); });
  document.getElementById('language').textContent = language === 'ko' ? 'English' : '한국어';
  const titles = {
    email: text(manage ? '연결 관리 로그인' : 'BuddyStudy 로그인', manage ? 'Manage connections' : 'Sign in to BuddyStudy'),
    code: text('이메일을 확인하세요', 'Check your email'),
    consent: text('ChatGPT에 연결', 'Connect to ChatGPT'),
    connections: text('연결된 앱', 'Connected apps'),
  };
  const descriptions = {
    email: text('연결할 계정의 이메일을 입력하세요.', 'Enter your BuddyStudy account email.'),
    code: text(`${emailInput.value.trim()} 메일함을 확인하세요.`, `Enter the code sent to ${emailInput.value.trim()}.`),
    consent: text(grantedScopes.has('learning:write') ? 'ChatGPT에서 내 학습을 이어갑니다.' : 'BuddyStudy 학습 기록을 공유합니다.', grantedScopes.has('learning:write') ? 'Continue your learning in ChatGPT.' : 'Share your BuddyStudy learning records.'),
    connections: text('내 학습 기록에 접근할 수 있는 앱입니다.', 'Apps with access to your learning records.'),
  };
  document.getElementById('screen-title').textContent = titles[stage];
  document.getElementById('screen-description').textContent = descriptions[stage];
  message.textContent = '';
  if (stage === 'connections' && activeConnections !== null) renderConnections();
}
function setStage(next) {
  stage = next;
  document.getElementById('login').hidden = !['email', 'code'].includes(stage);
  document.getElementById('email-form').hidden = stage !== 'email';
  document.getElementById('code-form').hidden = stage !== 'code';
  document.getElementById('consent').hidden = stage !== 'consent';
  document.getElementById('connections').hidden = stage !== 'connections';
  translate();
  if (stage === 'code') document.getElementById('code').focus();
  else if (stage !== 'email') document.getElementById('screen-title').focus();
}
document.getElementById('language').addEventListener('click', () => { language = language === 'ko' ? 'en' : 'ko'; translate(); });
translate();
async function request(path, body) {
  if (!id || !/^[A-Za-z0-9_-]{43}$/.test(id)) throw new Error(text('ChatGPT에서 연결을 다시 시작해 주세요.', 'Restart the connection from ChatGPT.'));
  const response = await fetch(path, {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-OAuth-Intent': 'connect'}, body: JSON.stringify({id, ...body})});
  if (!response.ok) throw new Error(text('요청이 만료되었습니다. 연결을 다시 시작해 주세요.', 'This request expired. Restart the connection.'));
  return response.json();
}
async function busy(action) {
  document.querySelectorAll('button').forEach(button => { button.disabled = true; });
  message.textContent = '';
  try { await action(); } catch (error) { message.textContent = error.message; }
  finally { document.querySelectorAll('button').forEach(button => { button.disabled = false; }); }
}
document.getElementById('email-form').addEventListener('submit', event => {
  event.preventDefault();
  busy(async () => {
    const result = await request('/oauth/email-code', {email: emailInput.value});
    if (!result.sent) throw new Error(text('요청이 많습니다. 잠시 후 다시 시도해 주세요.', 'Too many requests. Try again later.'));
    setStage('code');
    message.textContent = text('가입된 이메일로만 발송됩니다. 10분 안에 입력해 주세요.', 'Codes are sent to existing accounts only. Enter within 10 minutes.');
  });
});
document.getElementById('change-email').addEventListener('click', () => {
  document.getElementById('code').value = '';
  setStage('email');
  emailInput.focus();
});
document.getElementById('code-form').addEventListener('submit', event => {
  event.preventDefault();
  busy(async () => {
    const result = await request('/oauth/verify', {code: document.getElementById('code').value});
    if (!result.verified) throw new Error(text('인증번호를 확인해 주세요. 최대 5회까지 입력할 수 있습니다.', 'Check your code. A maximum of five attempts is allowed.'));
    const session = await request('/oauth/session', {});
    grantedScopes = new Set(session.scopes);
    document.getElementById('account-email').textContent = emailInput.value.trim().toLowerCase();
    setStage(manage ? 'connections' : 'consent');
    if (manage) await showConnections();
  });
});
document.getElementById('change-account').addEventListener('click', () => busy(async () => {
  const result = await request('/oauth/restart', {});
  location.assign(result.redirect);
}));
for (const [button, allow] of [['allow', true], ['deny', false]]) {
  document.getElementById(button).addEventListener('click', () => busy(async () => {
    const result = await request('/oauth/consent', {allow});
    location.assign(result.redirect);
  }));
}
async function showConnections() {
  activeConnections = await request('/oauth/connections', {});
  renderConnections();
}
function renderConnections() {
  const list = document.getElementById('connection-list');
  list.replaceChildren();
  if (activeConnections.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'hint';
    empty.textContent = text('연결된 앱이 없습니다.', 'No connected apps.');
    list.append(empty);
  }
  for (const connection of activeConnections) {
    const row = document.createElement('div');
    row.className = 'connection-row';
    const identity = document.createElement('div');
    const label = document.createElement('strong');
    label.textContent = connection.clientId === 'buddystudy-chatgpt' ? 'ChatGPT' : connection.clientId;
    const expiry = document.createElement('span');
    expiry.textContent = `${text('만료', 'Expires')} ${new Date(connection.expiresAt).toLocaleDateString(language)}`;
    identity.append(label, expiry);
    const revoke = document.createElement('button');
    revoke.className = 'text-button';
    revoke.type = 'button';
    revoke.textContent = text('연결 해제', 'Disconnect');
    revoke.addEventListener('click', () => busy(async () => {
      await request('/oauth/disconnect', {connectionId: connection.id});
      await showConnections();
      message.textContent = text('연결을 해제했습니다.', 'Disconnected.');
    }));
    row.append(identity, revoke);
    list.append(row);
  }
}
