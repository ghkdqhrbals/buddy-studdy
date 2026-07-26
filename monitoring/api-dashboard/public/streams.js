import {
  adminFetch,
  clearAdminSession,
  hasValidAdminSession,
  loginAdmin,
} from "./admin-session.js";
import {
  buildStreamEntriesPath,
  buildStreamEntryPath,
  isRedisStreamId,
  summarizeGroups,
} from "./stream-model.js";

const state = {
  topics: [],
  topic: "",
  cursorStack: [""],
  pageIndex: 0,
  nextCursor: "",
  selectedEntryId: "",
};

const elements = {
  loginPanel: document.querySelector("#adminLoginPanel"),
  loginForm: document.querySelector("#adminLoginForm"),
  loginStatus: document.querySelector("#adminLoginStatus"),
  username: document.querySelector("#adminUsername"),
  password: document.querySelector("#adminPassword"),
  workspace: document.querySelector("#adminWorkspace"),
  logout: document.querySelector("#adminLogoutButton"),
  topicQuery: document.querySelector("#streamTopicQuery"),
  search: document.querySelector("#streamSearchButton"),
  topic: document.querySelector("#streamTopicSelect"),
  eventType: document.querySelector("#streamEventType"),
  pageSize: document.querySelector("#streamPageSize"),
  refresh: document.querySelector("#streamRefreshButton"),
  status: document.querySelector("#streamStatus"),
  key: document.querySelector("#streamKey"),
  length: document.querySelector("#streamLength"),
  firstId: document.querySelector("#streamFirstId"),
  lastId: document.querySelector("#streamLastId"),
  groups: document.querySelector("#streamGroups"),
  entryId: document.querySelector("#streamEntryId"),
  findEntry: document.querySelector("#streamEntryFindButton"),
  clearEntry: document.querySelector("#streamEntryClearButton"),
  rows: document.querySelector("#streamEntryRows"),
  empty: document.querySelector("#streamEntriesEmpty"),
  previous: document.querySelector("#streamPreviousButton"),
  next: document.querySelector("#streamNextButton"),
  pageLabel: document.querySelector("#streamPageLabel"),
  detail: document.querySelector("#streamEntryDetail"),
  detailMeta: document.querySelector("#streamEntryDetailMeta"),
  detailJson: document.querySelector("#streamEntryDetailJson"),
  closeDetail: document.querySelector("#streamEntryDetailClose"),
};

function setStatus(message, tone = "") {
  elements.status.textContent = message;
  elements.status.dataset.tone = tone;
}

function setAuthenticated(authenticated) {
  elements.loginPanel.hidden = authenticated;
  elements.workspace.hidden = !authenticated;
  elements.logout.hidden = !authenticated;
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

function handleError(error) {
  if (error.status === 401) {
    logout("로그인이 만료되었습니다. 다시 로그인해 주세요.");
    return;
  }
  setStatus(error.message, "error");
}

function selectedTopic() {
  return state.topics.find((topic) => topic.topic === state.topic) || null;
}

function renderTopics() {
  elements.topic.replaceChildren();
  for (const topic of state.topics) {
    const option = document.createElement("option");
    option.value = topic.topic;
    option.textContent = topic.topic;
    elements.topic.append(option);
  }
  elements.topic.disabled = state.topics.length === 0;
}

function renderSummary() {
  const topic = selectedTopic();
  elements.key.textContent = topic?.streamKey || "-";
  elements.length.textContent = topic
    ? `${Number(topic.length).toLocaleString()} / ${Number(topic.maxLength).toLocaleString()}`
    : "-";
  elements.firstId.textContent = topic?.firstEntryId || "-";
  elements.lastId.textContent = topic?.lastEntryId || "-";
  elements.groups.textContent = topic ? summarizeGroups(topic.groups) : "-";
}

function cell(value, className = "") {
  const element = document.createElement("td");
  element.textContent = value == null || value === "" ? "-" : String(value);
  if (className) element.className = className;
  return element;
}

function showEntryDetail(entry) {
  state.selectedEntryId = entry.id;
  elements.detail.hidden = false;
  elements.detailMeta.textContent = `${entry.eventType || "Unknown event"} · ${entry.id}`;
  elements.detailJson.textContent = JSON.stringify(entry.fields || {}, null, 2);
  for (const row of elements.rows.querySelectorAll("tr")) {
    row.classList.toggle("selected", row.dataset.entryId === entry.id);
  }
}

function hideEntryDetail() {
  state.selectedEntryId = "";
  elements.detail.hidden = true;
  elements.detailJson.textContent = "";
  for (const row of elements.rows.querySelectorAll("tr")) row.classList.remove("selected");
}

function renderEntries(entries) {
  const safeEntries = Array.isArray(entries) ? entries : [];
  elements.rows.replaceChildren();
  elements.empty.hidden = safeEntries.length > 0;
  for (const entry of safeEntries) {
    const row = document.createElement("tr");
    row.dataset.entryId = entry.id;
    row.tabIndex = 0;
    row.append(
      cell(entry.id, "mono"),
      cell(entry.eventType),
      cell(entry.eventId, "mono"),
      cell(entry.recordId),
      cell(entry.userId),
      cell(entry.deviceId, "mono"),
    );
    const open = () => showEntryDetail(entry);
    row.addEventListener("click", open);
    row.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        open();
      }
    });
    elements.rows.append(row);
  }
}

function resetCursor() {
  state.cursorStack = [""];
  state.pageIndex = 0;
  state.nextCursor = "";
}

function updatePagination(hasMore) {
  elements.pageLabel.textContent = `Page ${state.pageIndex + 1}`;
  elements.previous.disabled = state.pageIndex === 0;
  elements.next.disabled = !hasMore;
}

async function loadEntries() {
  if (!state.topic) {
    renderEntries([]);
    updatePagination(false);
    return;
  }
  setStatus("Loading...", "loading");
  hideEntryDetail();
  const cursor = state.cursorStack[state.pageIndex] || "";
  try {
    const page = await adminFetch(buildStreamEntriesPath(state.topic, {
      cursor,
      limit: Number(elements.pageSize.value),
      eventType: elements.eventType.value,
    }));
    if (!page || !Array.isArray(page.items)) throw new Error("Stream entry response is invalid.");
    state.nextCursor = page.nextCursor || "";
    renderEntries(page.items);
    updatePagination(Boolean(page.hasMore && state.nextCursor));
    setStatus(`${page.items.length.toLocaleString()} entries`, "ready");
  } catch (error) {
    handleError(error);
  }
}

async function loadTopics(query = "") {
  setStatus("Loading streams...", "loading");
  try {
    const params = new URLSearchParams();
    if (query.trim()) params.set("query", query.trim());
    const suffix = params.size ? `?${params}` : "";
    const topics = await adminFetch(`/event-streams/topics${suffix}`);
    if (!Array.isArray(topics)) throw new Error("Stream topic response is invalid.");
    state.topics = topics;
    if (!topics.some((topic) => topic.topic === state.topic)) {
      state.topic = topics[0]?.topic || "";
    }
    renderTopics();
    elements.topic.value = state.topic;
    renderSummary();
    resetCursor();
    await loadEntries();
  } catch (error) {
    handleError(error);
  }
}

async function findExactEntry() {
  const entryId = elements.entryId.value.trim();
  if (!isRedisStreamId(entryId)) {
    setStatus("Use a Redis Stream ID such as 1785000998000-0.", "error");
    elements.entryId.focus();
    return;
  }
  if (!state.topic) return;
  setStatus("Finding entry...", "loading");
  try {
    const entry = await adminFetch(buildStreamEntryPath(state.topic, entryId));
    renderEntries([entry]);
    updatePagination(false);
    showEntryDetail(entry);
    elements.clearEntry.hidden = false;
    setStatus("Exact entry loaded", "ready");
  } catch (error) {
    handleError(error);
  }
}

elements.loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  elements.loginStatus.textContent = "Signing in...";
  try {
    await loginAdmin(elements.username.value, elements.password.value);
    elements.loginStatus.textContent = "";
    setAuthenticated(true);
    await loadTopics();
  } catch (error) {
    elements.loginStatus.textContent = error.message;
    elements.loginStatus.dataset.tone = "error";
  }
});

elements.logout.addEventListener("click", () => logout());
elements.search.addEventListener("click", () => loadTopics(elements.topicQuery.value));
elements.topicQuery.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    elements.search.click();
  }
});
elements.topic.addEventListener("change", () => {
  state.topic = elements.topic.value;
  renderSummary();
  resetCursor();
  loadEntries();
});
elements.eventType.addEventListener("change", () => {
  resetCursor();
  loadEntries();
});
elements.eventType.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    resetCursor();
    loadEntries();
  }
});
elements.pageSize.addEventListener("change", () => {
  resetCursor();
  loadEntries();
});
elements.refresh.addEventListener("click", () => loadTopics(elements.topicQuery.value));
elements.previous.addEventListener("click", () => {
  if (state.pageIndex === 0) return;
  state.pageIndex -= 1;
  loadEntries();
});
elements.next.addEventListener("click", () => {
  if (!state.nextCursor) return;
  state.cursorStack[state.pageIndex + 1] = state.nextCursor;
  state.pageIndex += 1;
  loadEntries();
});
elements.findEntry.addEventListener("click", findExactEntry);
elements.entryId.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    findExactEntry();
  }
});
elements.clearEntry.addEventListener("click", () => {
  elements.entryId.value = "";
  elements.clearEntry.hidden = true;
  resetCursor();
  loadEntries();
});
elements.closeDetail.addEventListener("click", hideEntryDetail);

const authenticated = hasValidAdminSession();
setAuthenticated(authenticated);
if (authenticated) loadTopics();
