import {
  adminFetch,
  clearAdminSession,
  hasValidAdminSession,
  loginAdmin,
} from "./admin-session.js";

const PAGE_SIZE = 20;

const state = {
  query: "",
  offset: 0,
  totalCount: 0,
  tiers: [],
};

const elements = {
  loginPanel: document.querySelector("#adminLoginPanel"),
  loginForm: document.querySelector("#adminLoginForm"),
  loginStatus: document.querySelector("#adminLoginStatus"),
  username: document.querySelector("#adminUsername"),
  password: document.querySelector("#adminPassword"),
  workspace: document.querySelector("#adminWorkspace"),
  logout: document.querySelector("#adminLogoutButton"),
  search: document.querySelector("#adminUserSearch"),
  searchButton: document.querySelector("#adminUserSearchButton"),
  userStatus: document.querySelector("#adminUserStatus"),
  userCount: document.querySelector("#adminUserCount"),
  userRows: document.querySelector("#adminUserRows"),
  usersEmpty: document.querySelector("#adminUsersEmpty"),
  tiers: document.querySelector("#tierSettingsRows"),
  previous: document.querySelector("#adminPreviousButton"),
  next: document.querySelector("#adminNextButton"),
  pageLabel: document.querySelector("#adminPageLabel"),
};

function setAuthenticated(authenticated) {
  elements.loginPanel.hidden = authenticated;
  elements.workspace.hidden = !authenticated;
  elements.logout.hidden = !authenticated;
}

function formatReset(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "-"
    : new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function escapeHTML(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function renderTierSettings() {
  elements.tiers.replaceChildren();
  for (const tier of state.tiers) {
    const row = document.createElement("form");
    row.className = "tier-settings-row";
    row.innerHTML = `
      <div>
        <strong>${escapeHTML(tier.tierCode)}</strong>
        <span>${escapeHTML(tier.description)}</span>
      </div>
      <label>
        Monthly questions
        <input type="number" min="0" max="1000000" value="${Number(tier.monthlyQuestionLimit)}" />
      </label>
      <button type="submit" class="secondary-button">Save</button>
      <span class="status-message" aria-live="polite"></span>
    `;
    row.addEventListener("submit", async (event) => {
      event.preventDefault();
      const status = row.querySelector(".status-message");
      const monthlyQuestionLimit = Number(row.querySelector("input").value);
      status.textContent = "Saving...";
      try {
        const updated = await adminFetch(`/membership-tiers/${encodeURIComponent(tier.tierCode)}`, {
          method: "PATCH",
          body: JSON.stringify({ monthlyQuestionLimit }),
        });
        Object.assign(tier, updated);
        status.textContent = "Saved";
        status.dataset.tone = "ready";
        await loadUsers();
      } catch (error) {
        status.textContent = error.message;
        status.dataset.tone = "error";
      }
    });
    elements.tiers.append(row);
  }
}

function tierOptions(selected) {
  return state.tiers
    .map((tier) => {
      const code = escapeHTML(tier.tierCode);
      return `<option value="${code}"${tier.tierCode === selected ? " selected" : ""}>${code}</option>`;
    })
    .join("");
}

function renderUsers(users) {
  const safeUsers = Array.isArray(users) ? users : [];
  elements.userRows.replaceChildren();
  elements.usersEmpty.hidden = safeUsers.length > 0;
  for (const user of safeUsers) {
    const displayName = escapeHTML(user.displayName || "(no name)");
    const email = escapeHTML(user.email || `User ${user.id}`);
    const provider = escapeHTML(user.provider);
    const statusText = escapeHTML(user.status);
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>
        <strong>${displayName}</strong>
        <span>${email}</span>
        <small>ID ${Number(user.id)} · ${provider}</small>
      </td>
      <td><span class="admin-status-pill">${statusText}</span></td>
      <td>
        <strong>${Number(user.remainingCount)} remaining</strong>
        <span>${Number(user.usedCount)} / ${Number(user.monthlyLimit)} used</span>
      </td>
      <td>${escapeHTML(formatReset(user.resetAt))}</td>
      <td><select aria-label="Internal plan for ${displayName}">${tierOptions(user.tierCode)}</select></td>
      <td>
        <input class="admin-limit-override" type="number" min="0" max="1000000"
          value="${user.monthlyLimitOverride == null ? "" : Number(user.monthlyLimitOverride)}" placeholder="Plan default"
          aria-label="Personal monthly limit for ${displayName}" />
      </td>
      <td>
        <button type="button" class="secondary-button">Save</button>
        <span class="status-message" aria-live="polite"></span>
      </td>
    `;
    row.querySelector("button").addEventListener("click", async () => {
      const status = row.querySelector(".status-message");
      const overrideText = row.querySelector(".admin-limit-override").value.trim();
      const payload = {
        tierCode: row.querySelector("select").value,
        monthlyQuestionLimitOverride: overrideText === "" ? null : Number(overrideText),
      };
      status.textContent = "Saving...";
      try {
        await adminFetch(`/users/${user.id}/membership`, {
          method: "PATCH",
          body: JSON.stringify(payload),
        });
        status.textContent = "Saved";
        status.dataset.tone = "ready";
        await loadUsers();
      } catch (error) {
        status.textContent = error.message;
        status.dataset.tone = "error";
      }
    });
    elements.userRows.append(row);
  }
}

function updatePagination() {
  const page = Math.floor(state.offset / PAGE_SIZE) + 1;
  const pageCount = Math.max(1, Math.ceil(state.totalCount / PAGE_SIZE));
  elements.pageLabel.textContent = `Page ${page} of ${pageCount}`;
  elements.previous.disabled = state.offset === 0;
  elements.next.disabled = state.offset + PAGE_SIZE >= state.totalCount;
  elements.userCount.textContent = `${state.totalCount.toLocaleString()} users`;
}

async function loadUsers() {
  elements.userStatus.textContent = "Loading...";
  const params = new URLSearchParams({
    limit: String(PAGE_SIZE),
    offset: String(state.offset),
  });
  if (state.query) params.set("query", state.query);
  try {
    const page = await adminFetch(`/users?${params}`);
    if (!page || !Array.isArray(page.users)) {
      throw new Error("사용자 목록 응답 형식이 올바르지 않습니다.");
    }
    state.totalCount = Number(page.totalCount) || 0;
    renderUsers(page.users);
    updatePagination();
    elements.userStatus.textContent = "Ready";
    elements.userStatus.dataset.tone = "ready";
  } catch (error) {
    if (error.status === 401) logout("로그인이 만료되었습니다. 다시 로그인해 주세요.");
    elements.userStatus.textContent = error.message;
    elements.userStatus.dataset.tone = "error";
  }
}

async function loadWorkspace() {
  const tiers = await adminFetch("/membership-tiers");
  if (!Array.isArray(tiers)) {
    throw new Error("요금제 응답 형식이 올바르지 않습니다.");
  }
  state.tiers = tiers;
  renderTierSettings();
  await loadUsers();
}

function logout(message = "") {
  clearAdminSession();
  setAuthenticated(false);
  elements.password.value = "";
  if (message) {
    elements.loginStatus.textContent = message;
    elements.loginStatus.dataset.tone = "error";
  }
}

elements.loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  elements.loginStatus.textContent = "Signing in...";
  try {
    await loginAdmin(elements.username.value, elements.password.value);
    setAuthenticated(true);
    await loadWorkspace();
  } catch (error) {
    elements.loginStatus.textContent = error.message;
    elements.loginStatus.dataset.tone = "error";
  }
});

elements.logout.addEventListener("click", () => logout());
elements.searchButton.addEventListener("click", () => {
  state.query = elements.search.value.trim();
  state.offset = 0;
  loadUsers();
});
elements.search.addEventListener("keydown", (event) => {
  if (event.key !== "Enter") return;
  event.preventDefault();
  elements.searchButton.click();
});
elements.previous.addEventListener("click", () => {
  state.offset = Math.max(0, state.offset - PAGE_SIZE);
  loadUsers();
});
elements.next.addEventListener("click", () => {
  state.offset += PAGE_SIZE;
  loadUsers();
});

const authenticated = hasValidAdminSession();
setAuthenticated(authenticated);
if (authenticated) {
  loadWorkspace().catch((error) => {
    elements.userStatus.textContent = error.message;
    elements.userStatus.dataset.tone = "error";
    if (error.status === 401) logout("로그인이 만료되었습니다. 다시 로그인해 주세요.");
  });
}
