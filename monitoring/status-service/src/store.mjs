import fs from "node:fs/promises";
import path from "node:path";

const EMPTY_STATE = Object.freeze({
  version: 1,
  nextId: 1,
  windows: [],
});

function isoDate(value, field, required = true) {
  if ((value === null || value === undefined || value === "") && !required) {
    return null;
  }
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    throw new StatusValidationError(`${field} must be a valid date.`);
  }
  return date.toISOString();
}

function requiredText(value, field, maximum) {
  const text = String(value ?? "").trim();
  if (!text) throw new StatusValidationError(`${field} is required.`);
  if (text.length > maximum) {
    throw new StatusValidationError(`${field} exceeds ${maximum} characters.`);
  }
  return text;
}

function stateFor(window, now = Date.now()) {
  const startsAt = Date.parse(window.startsAt);
  const endsAt = window.endsAt ? Date.parse(window.endsAt) : null;
  const terminatedAt = window.terminatedAt ? Date.parse(window.terminatedAt) : null;
  if (terminatedAt !== null && terminatedAt < startsAt) return "CANCELLED";
  if (terminatedAt !== null) return "COMPLETED";
  if (startsAt > now) return "SCHEDULED";
  if (endsAt !== null && endsAt <= now) return "COMPLETED";
  return "ACTIVE";
}

function overlaps(left, right) {
  const leftStart = Date.parse(left.startsAt);
  const rightStart = Date.parse(right.startsAt);
  const leftEnd = left.endsAt ? Date.parse(left.endsAt) : Number.POSITIVE_INFINITY;
  const rightEnd = right.endsAt ? Date.parse(right.endsAt) : Number.POSITIVE_INFINITY;
  return leftStart < rightEnd && rightStart < leftEnd;
}

export class StatusValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "StatusValidationError";
  }
}

export class StatusNotFoundError extends Error {
  constructor(message) {
    super(message);
    this.name = "StatusNotFoundError";
  }
}

export function localizedContent(window, acceptLanguage = "") {
  const language = String(acceptLanguage)
    .split(",")[0]
    .trim()
    .replaceAll("_", "-")
    .split("-")[0]
    .toLowerCase();
  if (language === "ko") {
    return { title: window.content.titleKo, message: window.content.messageKo };
  }
  if (language === "ja") {
    return { title: window.content.titleJa, message: window.content.messageJa };
  }
  return { title: window.content.titleEn, message: window.content.messageEn };
}

export class ServiceStatusStore {
  constructor(dataDirectory) {
    this.dataDirectory = path.resolve(dataDirectory);
    this.statePath = path.join(this.dataDirectory, "service-maintenance.json");
    this.state = structuredClone(EMPTY_STATE);
    this.writeChain = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.dataDirectory, { recursive: true });
    try {
      const parsed = JSON.parse(await fs.readFile(this.statePath, "utf8"));
      this.state = {
        version: 1,
        nextId: Math.max(1, Number(parsed.nextId) || 1),
        windows: Array.isArray(parsed.windows) ? parsed.windows : [],
      };
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      await this.persist();
    }
    return this;
  }

  active(now = Date.now()) {
    return this.state.windows
      .filter((window) => stateFor(window, now) === "ACTIVE")
      .sort((left, right) => Date.parse(right.startsAt) - Date.parse(left.startsAt))[0] ?? null;
  }

  overview(now = Date.now()) {
    const upcoming = this.state.windows
      .filter((window) => stateFor(window, now) === "SCHEDULED")
      .sort((left, right) => Date.parse(left.startsAt) - Date.parse(right.startsAt))
      .slice(0, 20);
    return {
      current: this.active(now),
      upcoming,
      checkedAt: new Date(now).toISOString(),
    };
  }

  history(limit = 20, offset = 0) {
    const boundedLimit = Math.min(Math.max(Number(limit) || 20, 1), 100);
    const boundedOffset = Math.max(Number(offset) || 0, 0);
    const windows = [...this.state.windows]
      .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
    return {
      items: windows.slice(boundedOffset, boundedOffset + boundedLimit),
      totalCount: windows.length,
      limit: boundedLimit,
      offset: boundedOffset,
    };
  }

  async create(input, actor = "monitoring-admin") {
    const startsAt = isoDate(input.startsAt, "startsAt");
    const endsAt = isoDate(input.endsAt, "endsAt", false);
    if (endsAt && Date.parse(endsAt) <= Date.parse(startsAt)) {
      throw new StatusValidationError("endsAt must be after startsAt.");
    }
    const candidate = { startsAt, endsAt };
    const conflict = this.state.windows.some((window) => {
      const state = stateFor(window);
      return (state === "ACTIVE" || state === "SCHEDULED") && overlaps(window, candidate);
    });
    if (conflict) {
      throw new StatusValidationError("The maintenance window overlaps an active or scheduled window.");
    }

    const now = new Date().toISOString();
    const window = {
      id: this.state.nextId,
      content: {
        titleKo: requiredText(input.titleKo, "titleKo", 120),
        titleEn: requiredText(input.titleEn, "titleEn", 120),
        titleJa: requiredText(input.titleJa, "titleJa", 120),
        messageKo: requiredText(input.messageKo, "messageKo", 1_000),
        messageEn: requiredText(input.messageEn, "messageEn", 1_000),
        messageJa: requiredText(input.messageJa, "messageJa", 1_000),
      },
      startsAt,
      endsAt,
      createdAt: now,
      createdBy: String(actor || "monitoring-admin").slice(0, 120),
      terminatedAt: null,
      terminatedBy: null,
    };
    this.state.nextId += 1;
    this.state.windows.push(window);
    await this.persist();
    return window;
  }

  async terminate(id, actor = "monitoring-admin") {
    const window = this.state.windows.find((item) => item.id === Number(id));
    if (!window || !["ACTIVE", "SCHEDULED"].includes(stateFor(window))) {
      throw new StatusNotFoundError("Active or scheduled maintenance window was not found.");
    }
    window.terminatedAt = new Date().toISOString();
    window.terminatedBy = String(actor || "monitoring-admin").slice(0, 120);
    await this.persist();
    return window;
  }

  async persist() {
    const payload = `${JSON.stringify(this.state, null, 2)}\n`;
    const temporaryPath = `${this.statePath}.tmp`;
    this.writeChain = this.writeChain.then(async () => {
      await fs.writeFile(temporaryPath, payload, { mode: 0o600 });
      await fs.rename(temporaryPath, this.statePath);
    });
    return this.writeChain;
  }
}

export { stateFor };
