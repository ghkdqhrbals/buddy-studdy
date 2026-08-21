import { useEffect, useMemo, useState } from "react";
import {
  createNativeAdvertisementCampaign,
  fetchNativeAdvertisementCampaigns,
  updateNativeAdvertisementCampaign,
  type UnauthorizedHandler,
} from "./api";
import type {
  NativeAdvertisementCampaignInput,
  NativeAdvertisementCampaignSummary,
  NativeAdvertisementRankingPolicy,
} from "./types";

const PAGE_SIZE = 20;

const initialForm: NativeAdvertisementCampaignInput = {
  campaignKey: "",
  audience: "ALL",
  disclosureKo: "(광고)",
  disclosureEn: "(Ad)",
  disclosureJa: "（広告）",
  titleKo: "",
  titleEn: "",
  titleJa: "",
  bodyKo: null,
  bodyEn: null,
  bodyJa: null,
  destinationUrl: "",
  basePriority: 1,
  authenticatedRelevance: 1,
  anonymousRelevance: 1,
  dailySelectionCap: 2,
  minimumSecondsBetweenSelections: 21_600,
  postViewCooldownSeconds: 604_800,
  minimumFeedItemCount: 4,
  earliestPosition: 2,
  latestPosition: 7,
  active: true,
  startsAt: null,
  endsAt: null,
};

export function AdvertisingPanel({
  onUnauthorized,
  refreshKey,
}: {
  onUnauthorized: UnauthorizedHandler;
  refreshKey: number;
}) {
  const [campaigns, setCampaigns] = useState<NativeAdvertisementCampaignSummary[]>([]);
  const [rankingPolicy, setRankingPolicy] = useState<NativeAdvertisementRankingPolicy | null>(null);
  const [totalCount, setTotalCount] = useState(0);
  const [offset, setOffset] = useState(0);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<NativeAdvertisementCampaignInput>(initialForm);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadCampaigns();
  }, [offset, refreshKey]);

  const selected = useMemo(
    () => campaigns.find((campaign) => campaign.id === editingId) ?? null,
    [campaigns, editingId],
  );

  async function loadCampaigns() {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchNativeAdvertisementCampaigns(onUnauthorized, PAGE_SIZE, offset);
      setCampaigns(page.campaigns);
      setTotalCount(page.totalCount);
      setRankingPolicy(page.rankingPolicy);
    } catch (cause) {
      setError(message(cause));
    } finally {
      setLoading(false);
    }
  }

  function beginEditing(campaign: NativeAdvertisementCampaignSummary) {
    setEditingId(campaign.id);
    setForm(toInput(campaign));
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function resetForm() {
    setEditingId(null);
    setForm(initialForm);
  }

  async function saveCampaign() {
    if (!isComplete(form)) return;
    setSaving(true);
    setError(null);
    try {
      if (editingId === null) {
        await createNativeAdvertisementCampaign(form, onUnauthorized);
        setOffset(0);
      } else {
        await updateNativeAdvertisementCampaign(editingId, form, onUnauthorized);
      }
      resetForm();
      await loadCampaigns();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="app-updates-page advertising-page">
      {error ? <div className="error-banner inline-error" role="alert">{error}</div> : null}

      <section className="app-update-panel">
        <div className="panel-header app-update-heading">
          <div>
            <h2>{editingId === null ? "Create advertising campaign" : `Edit ${selected?.campaignKey ?? "campaign"}`}</h2>
            <p>Coupang Partners links must use HTTPS on link.coupang.com or coupang.com. The app records the existing view event before opening the URL.</p>
          </div>
          <span className={`status-pill ${form.active ? "success" : ""}`}>{form.active ? "Eligible" : "Paused"}</span>
        </div>

        <div className="app-update-form-grid ad-form-grid">
          <Field label="Campaign key" hint="Lowercase letters, numbers, hyphens">
            <input value={form.campaignKey} placeholder="coupang-desk-lamp-august" onChange={(event) => update("campaignKey", event.target.value)} />
          </Field>
          <Field label="Audience">
            <select value={form.audience} onChange={(event) => update("audience", event.target.value as NativeAdvertisementCampaignInput["audience"])}>
              <option value="ALL">All users</option>
              <option value="AUTHENTICATED">Authenticated only</option>
              <option value="ANONYMOUS">Anonymous only</option>
            </select>
          </Field>
          <Field label="Coupang advertising URL" hint="Affiliate query parameters are preserved">
            <input className="wide-input" value={form.destinationUrl} placeholder="https://link.coupang.com/a/..." onChange={(event) => update("destinationUrl", event.target.value)} />
          </Field>
          <Field label="Active">
            <label className="ad-checkbox"><input type="checkbox" checked={form.active} onChange={(event) => update("active", event.target.checked)} /> Allow ranking and delivery</label>
          </Field>

          <Field label="제목 · 한국어"><input value={form.titleKo} onChange={(event) => update("titleKo", event.target.value)} /></Field>
          <Field label="Title · English"><input value={form.titleEn} onChange={(event) => update("titleEn", event.target.value)} /></Field>
          <Field label="タイトル · 日本語"><input value={form.titleJa} onChange={(event) => update("titleJa", event.target.value)} /></Field>
          <Field label="본문 · 한국어"><textarea rows={2} value={form.bodyKo ?? ""} onChange={(event) => update("bodyKo", event.target.value || null)} /></Field>
          <Field label="Body · English"><textarea rows={2} value={form.bodyEn ?? ""} onChange={(event) => update("bodyEn", event.target.value || null)} /></Field>
          <Field label="本文 · 日本語"><textarea rows={2} value={form.bodyJa ?? ""} onChange={(event) => update("bodyJa", event.target.value || null)} /></Field>

          <Field label="Base priority" hint="0–10 · strongest manual ranking input"><NumberInput value={form.basePriority} step={0.1} onChange={(value) => update("basePriority", value)} /></Field>
          <Field label="Authenticated relevance" hint="0–10"><NumberInput value={form.authenticatedRelevance} step={0.1} onChange={(value) => update("authenticatedRelevance", value)} /></Field>
          <Field label="Anonymous relevance" hint="0–10"><NumberInput value={form.anonymousRelevance} step={0.1} onChange={(value) => update("anonymousRelevance", value)} /></Field>
          <Field label="Daily cap per user"><NumberInput value={form.dailySelectionCap} onChange={(value) => update("dailySelectionCap", value)} /></Field>
          <Field label="Minimum repeat gap" hint="hours per user"><NumberInput value={form.minimumSecondsBetweenSelections / 3600} step={1} onChange={(value) => update("minimumSecondsBetweenSelections", Math.round(value * 3600))} /></Field>
          <Field label="Cooldown after view" hint="days per user"><NumberInput value={form.postViewCooldownSeconds / 86400} step={1} onChange={(value) => update("postViewCooldownSeconds", Math.round(value * 86400))} /></Field>
          <Field label="Minimum public items"><NumberInput value={form.minimumFeedItemCount} onChange={(value) => update("minimumFeedItemCount", value)} /></Field>
          <Field label="Position range" hint="0-based unified list index">
            <div className="ad-position-range"><NumberInput value={form.earliestPosition} onChange={(value) => update("earliestPosition", value)} /><span>to</span><NumberInput value={form.latestPosition} onChange={(value) => update("latestPosition", value)} /></div>
          </Field>
          <Field label="Starts at" hint="optional, local time"><input type="datetime-local" value={toLocalDateTime(form.startsAt)} onChange={(event) => update("startsAt", toInstant(event.target.value))} /></Field>
          <Field label="Ends at" hint="optional, local time"><input type="datetime-local" value={toLocalDateTime(form.endsAt)} onChange={(event) => update("endsAt", toInstant(event.target.value))} /></Field>
        </div>
        <details className="ad-disclosure-details">
          <summary>Advertising disclosure labels</summary>
          <div className="app-update-form-grid">
            <Field label="한국어"><input value={form.disclosureKo} onChange={(event) => update("disclosureKo", event.target.value)} /></Field>
            <Field label="English"><input value={form.disclosureEn} onChange={(event) => update("disclosureEn", event.target.value)} /></Field>
            <Field label="日本語"><input value={form.disclosureJa} onChange={(event) => update("disclosureJa", event.target.value)} /></Field>
          </div>
        </details>
        <div className="app-update-actions">
          {editingId !== null ? <button className="secondary-button" disabled={saving} onClick={resetForm}>Cancel editing</button> : null}
          <button className="primary-button" disabled={saving || !isComplete(form)} onClick={() => void saveCampaign()}>
            {saving ? "Saving…" : editingId === null ? "Create campaign" : "Save campaign"}
          </button>
        </div>
      </section>

      {rankingPolicy ? <RankingExplanation policy={rankingPolicy} /> : null}

      <section className="app-update-panel">
        <div className="panel-header app-update-heading">
          <div><h2>Campaigns</h2><p>Views are the existing authenticated selection view event; rate uses the same 30-day ranking window.</p></div>
          <span>{totalCount.toLocaleString()} campaigns</span>
        </div>
        <div className="admin-table-scroll">
          <table className="admin-table ad-campaign-table">
            <thead><tr><th>Campaign</th><th>Status</th><th>Audience</th><th>Priority</th><th>30d selected</th><th>30d viewed</th><th>View rate</th><th>Destination</th><th>Updated</th></tr></thead>
            <tbody>
              {campaigns.map((campaign) => (
                <tr key={campaign.id} className={editingId === campaign.id ? "selected" : ""} onClick={() => beginEditing(campaign)}>
                  <td><strong>{campaign.titleKo}</strong><small>{campaign.campaignKey}</small></td>
                  <td><span className={`status-pill ${campaignStatus(campaign) === "ACTIVE" ? "success" : ""}`}>{campaignStatus(campaign)}</span></td>
                  <td>{campaign.audience}</td>
                  <td>{campaign.basePriority.toFixed(1)}</td>
                  <td>{campaign.performanceSelections.toLocaleString()}</td>
                  <td>{campaign.performanceViews.toLocaleString()}</td>
                  <td><strong>{formatPercent(campaign.performanceViewRate)}</strong></td>
                  <td><a href={campaign.destinationUrl} target="_blank" rel="noreferrer" onClick={(event) => event.stopPropagation()}>Open URL</a></td>
                  <td>{formatDate(campaign.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && campaigns.length === 0 ? <p className="table-empty">No advertising campaigns yet.</p> : null}
        </div>
        <div className="pager ad-pager">
          <button className="secondary-button" disabled={offset === 0 || loading} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Previous</button>
          <span>{totalCount === 0 ? 0 : offset + 1}–{Math.min(totalCount, offset + campaigns.length)} of {totalCount}</span>
          <button className="secondary-button" disabled={offset + campaigns.length >= totalCount || loading} onClick={() => setOffset(offset + PAGE_SIZE)}>Next</button>
        </div>
      </section>
    </div>
  );

  function update<Key extends keyof NativeAdvertisementCampaignInput>(key: Key, value: NativeAdvertisementCampaignInput[Key]) {
    setForm((current) => ({ ...current, [key]: value }));
  }
}

function RankingExplanation({ policy }: { policy: NativeAdvertisementRankingPolicy }) {
  return (
    <section className="app-update-panel ad-ranking-panel">
      <div className="panel-header app-update-heading"><div><h2>Current server ranking</h2><p>The app does not rank or shuffle. It renders the unified PUBLIC_QUESTION / ADVERTISEMENT list in server order.</p></div><span>{policy.exploitationPercent}% exploit · {policy.explorationPercent}% explore</span></div>
      <div className="ad-ranking-formula">
        <strong>score = priority×{policy.basePriorityWeight} + audience relevance×{policy.relevanceWeight} + smoothed view rate×{policy.smoothedViewRateWeight} + exploration×{policy.explorationWeight} + freshness×{policy.freshnessWeight} − today selections×{policy.dailySelectionPenalty}</strong>
        <p>Eligible campaigns first pass active dates, audience, per-user daily cap, repeat gap, post-view cooldown, destination safety, and minimum feed size. The server usually chooses rank #1, but explores rank #2–#{policy.selectionPoolSize} {policy.explorationPercent}% of the time. Position is then randomized only inside each campaign’s allowed range.</p>
      </div>
    </section>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return <label className="app-update-field"><span>{label}</span>{children}{hint ? <small>{hint}</small> : null}</label>;
}

function NumberInput({ value, step = 1, onChange }: { value: number; step?: number; onChange: (value: number) => void }) {
  return <input type="number" min="0" step={step} value={value} onChange={(event) => onChange(Number(event.target.value))} />;
}

function isComplete(form: NativeAdvertisementCampaignInput) {
  return Boolean(form.campaignKey.trim() && form.destinationUrl.trim() && form.titleKo.trim() && form.titleEn.trim() && form.titleJa.trim());
}

function toInput(campaign: NativeAdvertisementCampaignSummary): NativeAdvertisementCampaignInput {
  const { id: _id, placement: _placement, performanceSelections: _selections, performanceViews: _views, performanceViewRate: _rate, createdAt: _created, updatedAt: _updated, ...input } = campaign;
  return input;
}

function toLocalDateTime(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function toInstant(value: string) {
  return value ? new Date(value).toISOString() : null;
}

function campaignStatus(campaign: NativeAdvertisementCampaignSummary) {
  if (!campaign.active) return "PAUSED";
  const now = Date.now();
  if (campaign.startsAt && new Date(campaign.startsAt).getTime() > now) return "SCHEDULED";
  if (campaign.endsAt && new Date(campaign.endsAt).getTime() <= now) return "ENDED";
  return "ACTIVE";
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function message(cause: unknown) {
  return cause instanceof Error ? cause.message : "Request failed";
}
