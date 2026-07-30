import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import {
  createAppUpdateCampaign,
  endAppUpdateCampaign,
  fetchAppUpdateCampaigns,
  fetchAppUpdateUsers,
  type UnauthorizedHandler,
} from "./api";
import type {
  AppUpdateCampaignSummary,
  AppUpdateMode,
  AppUpdateUserSummary,
  CreateAppUpdateCampaignInput,
} from "./types";

const PAGE_SIZE = 20;
const DEFAULT_APP_STORE_URL = "https://apps.apple.com/app/id6774108938";

const initialForm: CreateAppUpdateCampaignInput = {
  platform: "ios",
  targetVersion: "",
  targetBuild: "",
  mode: "OPTIONAL",
  titleKo: "새 버전이 준비됐어요",
  titleEn: "A new version is ready",
  titleJa: "新しいバージョンがあります",
  messageKo: "더 안정적인 학습 경험을 위해 앱을 업데이트해 주세요.",
  messageEn: "Update the app for a more reliable study experience.",
  messageJa: "より安定した学習体験のため、アプリをアップデートしてください。",
  appStoreUrl: DEFAULT_APP_STORE_URL,
};

export function AppUpdatesPanel({
  onUnauthorized,
  refreshKey,
}: {
  onUnauthorized: UnauthorizedHandler;
  refreshKey: number;
}) {
  const [form, setForm] = useState<CreateAppUpdateCampaignInput>(initialForm);
  const [campaigns, setCampaigns] = useState<AppUpdateCampaignSummary[]>([]);
  const [campaignOffset, setCampaignOffset] = useState(0);
  const [campaignTotal, setCampaignTotal] = useState(0);
  const [selected, setSelected] = useState<AppUpdateCampaignSummary | null>(null);
  const [users, setUsers] = useState<AppUpdateUserSummary[]>([]);
  const [userOffset, setUserOffset] = useState(0);
  const [userTotal, setUserTotal] = useState(0);
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [loading, setLoading] = useState(false);
  const [userLoading, setUserLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadCampaigns();
  }, [campaignOffset, refreshKey]);

  useEffect(() => {
    if (!selected) {
      setUsers([]);
      setUserTotal(0);
      return;
    }
    void loadUsers(selected.id);
  }, [selected?.id, userOffset, query, status, refreshKey]);

  async function loadCampaigns() {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchAppUpdateCampaigns(onUnauthorized, PAGE_SIZE, campaignOffset);
      setCampaigns(page.campaigns);
      setCampaignTotal(page.totalCount);
      if (selected) {
        setSelected(page.campaigns.find((campaign) => campaign.id === selected.id) ?? selected);
      }
    } catch (cause) {
      setError(message(cause));
    } finally {
      setLoading(false);
    }
  }

  async function loadUsers(campaignId: number) {
    setUserLoading(true);
    setError(null);
    try {
      const page = await fetchAppUpdateUsers(campaignId, onUnauthorized, PAGE_SIZE, userOffset, query, status);
      setUsers(page.users);
      setUserTotal(page.totalCount);
    } catch (cause) {
      setError(message(cause));
    } finally {
      setUserLoading(false);
    }
  }

  async function createCampaign() {
    if (!form.targetVersion.trim() || !form.targetBuild.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const created = await createAppUpdateCampaign(form, onUnauthorized);
      setForm((value) => ({ ...value, targetVersion: "", targetBuild: "" }));
      setCampaignOffset(0);
      setSelected(created);
      setUserOffset(0);
      await loadCampaigns();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setSaving(false);
    }
  }

  async function endCampaign() {
    if (!selected) return;
    setSaving(true);
    setError(null);
    try {
      const ended = await endAppUpdateCampaign(selected.id, onUnauthorized);
      setSelected(ended);
      await loadCampaigns();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setSaving(false);
    }
  }

  const promptedConversion = useMemo(
    () => selected && selected.promptedUserCount > 0
      ? `${(selected.conversionRate * 100).toFixed(1)}%`
      : "—",
    [selected],
  );

  return (
    <div className="app-updates-page">
      {error ? <div className="error-banner inline-error" role="alert">{error}</div> : null}

      <section className="app-update-panel">
        <div className="panel-header app-update-heading">
          <div>
            <h2>Publish update guidance</h2>
            <p>Only users below the target version see it. Activating a campaign ends the previous active campaign.</p>
          </div>
          <span className={`status-pill ${form.mode === "FORCE" ? "danger" : ""}`}>
            {form.mode === "FORCE" ? "Blocking" : "Dismissible"}
          </span>
        </div>

        <div className="app-update-form-grid">
          <Field label="Target version">
            <input value={form.targetVersion} placeholder="1.1.0" onChange={(event) => update("targetVersion", event.target.value)} />
          </Field>
          <Field label="Target build">
            <input value={form.targetBuild} inputMode="numeric" placeholder="71" onChange={(event) => update("targetBuild", event.target.value)} />
          </Field>
          <Field label="Prompt behavior">
            <select value={form.mode} onChange={(event) => update("mode", event.target.value as AppUpdateMode)}>
              <option value="OPTIONAL">Optional · can dismiss</option>
              <option value="FORCE">Force · cannot dismiss</option>
            </select>
          </Field>
          <Field label="App Store URL">
            <input value={form.appStoreUrl} onChange={(event) => update("appStoreUrl", event.target.value)} />
          </Field>
          <Field label="제목 · 한국어">
            <input value={form.titleKo} onChange={(event) => update("titleKo", event.target.value)} />
          </Field>
          <Field label="Title · English">
            <input value={form.titleEn} onChange={(event) => update("titleEn", event.target.value)} />
          </Field>
          <Field label="タイトル · 日本語">
            <input value={form.titleJa} onChange={(event) => update("titleJa", event.target.value)} />
          </Field>
          <Field label="메시지 · 한국어">
            <textarea rows={3} value={form.messageKo} onChange={(event) => update("messageKo", event.target.value)} />
          </Field>
          <Field label="Message · English">
            <textarea rows={3} value={form.messageEn} onChange={(event) => update("messageEn", event.target.value)} />
          </Field>
          <Field label="メッセージ · 日本語">
            <textarea rows={3} value={form.messageJa} onChange={(event) => update("messageJa", event.target.value)} />
          </Field>
        </div>
        <div className="app-update-actions">
          <button
            className="primary-button"
            disabled={saving || !form.targetVersion.trim() || !form.targetBuild.trim()}
            onClick={() => void createCampaign()}
          >
            {saving ? "Publishing…" : "Activate campaign"}
          </button>
        </div>
      </section>

      <section className="app-update-panel">
        <div className="panel-header app-update-heading">
          <div>
            <h2>Campaign history</h2>
            <p>Conversion is a prompted user returning on the target version or newer.</p>
          </div>
          <span>{campaignTotal.toLocaleString()} campaigns</span>
        </div>
        <div className="admin-table-scroll">
          <table className="admin-table">
            <thead>
              <tr>
                <th>Target</th>
                <th>Mode</th>
                <th>Status</th>
                <th>Checked</th>
                <th>Prompted</th>
                <th>Opened</th>
                <th>Converted</th>
                <th>Rate</th>
                <th>Activated</th>
              </tr>
            </thead>
            <tbody>
              {campaigns.map((campaign) => (
                <tr
                  key={campaign.id}
                  className={selected?.id === campaign.id ? "selected" : ""}
                  onClick={() => {
                    setSelected(campaign);
                    setUserOffset(0);
                  }}
                >
                  <td><strong>{campaign.targetVersion}</strong><small>build {campaign.targetBuild}</small></td>
                  <td><span className={`status-pill ${campaign.mode === "FORCE" ? "danger" : ""}`}>{campaign.mode}</span></td>
                  <td><span className={`status-pill ${campaign.status === "ACTIVE" ? "success" : ""}`}>{campaign.status}</span></td>
                  <td>{campaign.checkedUserCount}</td>
                  <td>{campaign.promptedUserCount}</td>
                  <td>{campaign.openedUserCount}</td>
                  <td>{campaign.convertedUserCount}</td>
                  <td><strong>{formatRate(campaign.conversionRate, campaign.promptedUserCount)}</strong></td>
                  <td>{formatDate(campaign.activatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && campaigns.length === 0 ? <p className="table-empty">No update campaigns yet.</p> : null}
        </div>
        <SimplePager offset={campaignOffset} count={campaigns.length} total={campaignTotal} onChange={setCampaignOffset} />
      </section>

      {selected ? (
        <section className="app-update-panel">
          <div className="panel-header app-update-heading">
            <div>
              <h2>User conversion · iOS {selected.targetVersion} ({selected.targetBuild})</h2>
              <p>Each row shows the latest app version reported when that user entered the app.</p>
            </div>
            <div className="campaign-summary">
              <span><b>{selected.promptedUserCount}</b> prompted</span>
              <span><b>{selected.convertedUserCount}</b> converted</span>
              <span><b>{promptedConversion}</b> rate</span>
              {selected.status === "ACTIVE" ? (
                <button className="secondary-button" disabled={saving} onClick={() => void endCampaign()}>End campaign</button>
              ) : null}
            </div>
          </div>
          <form
            className="app-update-filters"
            onSubmit={(event) => {
              event.preventDefault();
              setUserOffset(0);
              setQuery(queryDraft.trim());
            }}
          >
            <input value={queryDraft} placeholder="Email, name, or user ID" aria-label="Search campaign users" onChange={(event) => setQueryDraft(event.target.value)} />
            <select value={status} aria-label="Conversion status" onChange={(event) => {
              setUserOffset(0);
              setStatus(event.target.value);
            }}>
              <option value="">All states</option>
              <option value="CONVERTED">Converted</option>
              <option value="OPENED">Store opened</option>
              <option value="DISMISSED">Dismissed</option>
              <option value="PROMPTED">Prompted</option>
              <option value="CHECKED">Checked only</option>
            </select>
            <button className="secondary-button" type="submit">Search</button>
          </form>
          <div className="admin-table-scroll">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Current version</th>
                  <th>First seen</th>
                  <th>State</th>
                  <th>Last check</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={`${user.userId}-${user.deviceId}`}>
                    <td><strong>{user.displayName || `User ${user.userId}`}</strong><small>{user.email}</small></td>
                    <td><strong>{user.currentVersion}</strong><small>build {user.currentBuild}</small></td>
                    <td>{user.firstVersion} ({user.firstBuild})</td>
                    <td><span className={`status-pill ${user.status === "CONVERTED" ? "success" : ""}`}>{user.status}</span></td>
                    <td>{formatDate(user.lastCheckedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!userLoading && users.length === 0 ? <p className="table-empty">No users have checked this campaign.</p> : null}
          </div>
          <SimplePager offset={userOffset} count={users.length} total={userTotal} onChange={setUserOffset} />
        </section>
      ) : null}
    </div>
  );

  function update<Key extends keyof CreateAppUpdateCampaignInput>(key: Key, value: CreateAppUpdateCampaignInput[Key]) {
    setForm((current) => ({ ...current, [key]: value }));
  }
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="app-update-field"><span>{label}</span>{children}</label>;
}

function SimplePager({
  offset,
  count,
  total,
  onChange,
}: {
  offset: number;
  count: number;
  total: number;
  onChange: (offset: number) => void;
}) {
  if (total <= PAGE_SIZE) return null;
  return (
    <div className="simple-pager">
      <span>{Math.min(offset + 1, total)}–{Math.min(offset + count, total)} of {total}</span>
      <div>
        <button className="secondary-button" disabled={offset === 0} onClick={() => onChange(Math.max(0, offset - PAGE_SIZE))}>Previous</button>
        <button className="secondary-button" disabled={offset + count >= total} onClick={() => onChange(offset + PAGE_SIZE)}>Next</button>
      </div>
    </div>
  );
}

function formatRate(rate: number, prompted: number): string {
  return prompted > 0 ? `${(rate * 100).toFixed(1)}%` : "—";
}

function formatDate(value?: string | null): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : "Request failed";
}
