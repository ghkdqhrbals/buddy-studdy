'use strict';
const id = new URLSearchParams(location.search).get('id');
const manage = new URLSearchParams(location.search).get('manage') === '1';
let language = navigator.language.startsWith('ko') ? 'ko' : 'en';
const message = document.getElementById('message');
let activeConnections = null;
const text = (ko, en) => language === 'ko' ? ko : en;
function translate() {
  document.documentElement.lang = language;
  const prefix = language === 'ko' ? '' : 'en/';
  document.getElementById('connection-notice').href = `https://ghkdqhrbals.github.io/buddy-studdy/${prefix}chatgpt-connection-2026-10-20.html`;
  document.getElementById('privacy').href = `https://ghkdqhrbals.github.io/buddy-studdy/${prefix}privacy.html`;
  document.querySelectorAll('[data-ko]').forEach(node => { node.textContent = node.dataset[language]; });
  document.getElementById('language').textContent = language === 'ko' ? 'English' : '한국어';
  message.textContent = '';
  if (activeConnections !== null) renderConnections();
}
document.getElementById('language').addEventListener('click', () => { language = language === 'ko' ? 'en' : 'ko'; translate(); });
translate();
async function request(path, body) {
  if (!id || !/^[A-Za-z0-9_-]{43}$/.test(id)) throw new Error(text('ChatGPT에서 연결을 다시 시작해 주세요.', 'Restart the connection from ChatGPT.'));
  const response = await fetch(path, {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-OAuth-Intent': 'connect'}, body: JSON.stringify({id, ...body})});
  if (!response.ok) throw new Error(text('연결 요청이 만료되었거나 처리할 수 없습니다. ChatGPT에서 다시 시작해 주세요.', 'The connection expired or could not be processed. Restart from ChatGPT.'));
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
    const result = await request('/oauth/email-code', {email: document.getElementById('email').value});
    if (!result.sent) throw new Error(text('요청이 많습니다. 잠시 후 다시 시도해 주세요.', 'Too many requests. Try again later.'));
    document.getElementById('email-form').hidden = true;
    document.getElementById('code-form').hidden = false;
    document.getElementById('code').focus();
    message.textContent = text('가입된 이메일이라면 인증번호가 전송됩니다. 연결을 시작한 시점부터 10분 안에 입력해 주세요.', 'If the email belongs to an existing account, a code has been sent. Enter it within 10 minutes of starting this connection.');
  });
});
document.getElementById('change-email').addEventListener('click', () => {
  document.getElementById('email-form').hidden = false;
  document.getElementById('code-form').hidden = true;
  document.getElementById('code').value = '';
});
document.getElementById('code-form').addEventListener('submit', event => {
  event.preventDefault();
  busy(async () => {
    const result = await request('/oauth/verify', {code: document.getElementById('code').value});
    if (!result.verified) throw new Error(text('인증번호를 확인해 주세요. 최대 5회까지 입력할 수 있습니다.', 'Check your code. A maximum of five attempts is allowed.'));
    document.getElementById('login').hidden = true;
    document.getElementById('consent').hidden = manage;
    await showConnections();
  });
});
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
  const connections = activeConnections;
  document.getElementById('connections').hidden = false;
  const list = document.getElementById('connection-list');
  list.replaceChildren();
  if (connections.length === 0) {
    const empty = document.createElement('p');
    empty.textContent = text('연결된 계정이 없습니다.', 'No active connections.');
    list.append(empty);
  }
  for (const connection of connections) {
    const row = document.createElement('p');
    const label = document.createElement('span');
    label.textContent = `${connection.clientId} · ${text('만료', 'Expires')} ${new Date(connection.expiresAt).toLocaleDateString()} `;
    const revoke = document.createElement('button');
    revoke.type = 'button';
    revoke.textContent = text('연결 해제', 'Disconnect');
    revoke.addEventListener('click', () => busy(async () => {
      await request('/oauth/disconnect', {connectionId: connection.id});
      await showConnections();
      message.textContent = text('연결을 해제했습니다.', 'Disconnected.');
    }));
    row.append(label, revoke);
    list.append(row);
  }
}
