import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import {
  createNativeAdvertisementCampaign,
  fetchNativeAdPlacementPolicy,
  fetchNativeAdvertisementCampaigns,
  fetchNativeAdvertisementCampaignUsers,
  updateNativeAdPlacementPolicy,
  updateNativeAdvertisementCampaign,
  type UnauthorizedHandler,
} from "./api";
import type {
  NativeAdPlacementPolicy,
  NativeAdPlacementPolicyInput,
  NativeAdvertisementCampaignAudienceFilter,
  NativeAdvertisementCampaignFilters,
  NativeAdvertisementCampaignInput,
  NativeAdvertisementCampaignStatusFilter,
  NativeAdvertisementCampaignSummary,
  NativeAdvertisementRankingPolicy,
  NativeAdvertisementUserPage,
  NativeAdvertisementUserStatusFilter,
  NativeAdvertisementUserSummary,
} from "./types";

const PAGE_SIZE = 20;
const emptyUserPage: NativeAdvertisementUserPage = { users: [], totalCount: 0, limit: PAGE_SIZE, offset: 0 };

type CampaignView = {
  offset: number;
  filters: NativeAdvertisementCampaignFilters;
};

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
  const [campaignQuery, setCampaignQuery] = useState("");
  const [appliedCampaignQuery, setAppliedCampaignQuery] = useState("");
  const [campaignStatusFilter, setCampaignStatusFilter] = useState<NativeAdvertisementCampaignStatusFilter>("");
  const [campaignAudienceFilter, setCampaignAudienceFilter] = useState<NativeAdvertisementCampaignAudienceFilter>("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<NativeAdvertisementCampaignInput>(initialForm);
  const [attemptedSubmit, setAttemptedSubmit] = useState(false);
  const [activityCampaignId, setActivityCampaignId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [campaignResultAnnouncement, setCampaignResultAnnouncement] = useState("");
  const editorRef = useRef<HTMLElement | null>(null);
  const validationRef = useRef<HTMLDivElement | null>(null);
  const campaignRequestIdRef = useRef(0);
  const campaignViewRef = useRef<CampaignView>({ offset: 0, filters: {} });

  useEffect(() => {
    const filters = {
      query: appliedCampaignQuery,
      status: campaignStatusFilter,
      audience: campaignAudienceFilter,
    };
    campaignViewRef.current = { offset, filters };
    void loadCampaigns(offset, filters);
  }, [offset, refreshKey, appliedCampaignQuery, campaignStatusFilter, campaignAudienceFilter]);

  const selected = useMemo(
    () => campaigns.find((campaign) => campaign.id === editingId) ?? null,
    [campaigns, editingId],
  );
  const pagePerformance = useMemo(() => {
    const selections = campaigns.reduce((sum, campaign) => sum + campaign.performanceSelections, 0);
    const opens = campaigns.reduce((sum, campaign) => sum + campaign.performanceViews, 0);
    return { selections, opens, rate: selections > 0 ? opens / selections : 0 };
  }, [campaigns]);
  const validationErrors = validateForm(form);
  const hasCampaignFilters = Boolean(appliedCampaignQuery || campaignStatusFilter || campaignAudienceFilter);

  async function loadCampaigns(nextOffset: number, filters: NativeAdvertisementCampaignFilters) {
    const requestId = ++campaignRequestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      const page = await fetchNativeAdvertisementCampaigns(onUnauthorized, PAGE_SIZE, nextOffset, filters);
      if (requestId !== campaignRequestIdRef.current) return;
      setCampaigns(page.campaigns);
      setTotalCount(page.totalCount);
      setRankingPolicy(page.rankingPolicy);
      setCampaignResultAnnouncement(`${page.totalCount.toLocaleString()} campaign results loaded.`);
      setActivityCampaignId((current) => {
        if (current !== null && page.campaigns.some((campaign) => campaign.id === current)) return current;
        return page.campaigns[0]?.id ?? null;
      });
    } catch (cause) {
      if (requestId !== campaignRequestIdRef.current) return;
      setError(message(cause));
    } finally {
      if (requestId === campaignRequestIdRef.current) setLoading(false);
    }
  }

  function showEditor() {
    window.requestAnimationFrame(() => editorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }

  function beginCreating() {
    setEditingId(null);
    setForm(initialForm);
    setAttemptedSubmit(false);
    setEditorOpen(true);
    showEditor();
  }

  function beginEditing(campaign: NativeAdvertisementCampaignSummary) {
    setEditingId(campaign.id);
    setForm(toInput(campaign));
    setAttemptedSubmit(false);
    setEditorOpen(true);
    showEditor();
  }

  function closeEditor() {
    setEditingId(null);
    setForm(initialForm);
    setAttemptedSubmit(false);
    setEditorOpen(false);
  }

  async function saveCampaign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAttemptedSubmit(true);
    if (validationErrors.length > 0) {
      window.requestAnimationFrame(() => {
        validationRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
        validationRef.current?.focus({ preventScroll: true });
      });
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const saved = editingId === null
        ? await createNativeAdvertisementCampaign(form, onUnauthorized)
        : await updateNativeAdvertisementCampaign(editingId, form, onUnauthorized);
      const latestView = campaignViewRef.current;
      const refreshedView = editingId === null ? { ...latestView, offset: 0 } : latestView;
      setOffset(refreshedView.offset);
      setActivityCampaignId(saved.id);
      closeEditor();
      campaignViewRef.current = refreshedView;
      await loadCampaigns(refreshedView.offset, refreshedView.filters);
    } catch (cause) {
      setError(message(cause));
    } finally {
      setSaving(false);
    }
  }

  function openAudience(campaignId: number) {
    setActivityCampaignId(campaignId);
    window.requestAnimationFrame(() => document.getElementById("ad-audience-activity")?.scrollIntoView({ behavior: "smooth", block: "start" }));
  }

  function applyCampaignSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = campaignQuery.trim();
    const filters = {
      query,
      status: campaignStatusFilter,
      audience: campaignAudienceFilter,
    };
    invalidateCampaignResults(0, filters);
    setOffset(0);
    setAppliedCampaignQuery(query);
    if (query === appliedCampaignQuery && offset === 0) void loadCampaigns(0, filters);
  }

  function changeCampaignStatus(value: NativeAdvertisementCampaignStatusFilter) {
    invalidateCampaignResults(0, {
      query: appliedCampaignQuery,
      status: value,
      audience: campaignAudienceFilter,
    });
    setOffset(0);
    setCampaignStatusFilter(value);
  }

  function changeCampaignAudience(value: NativeAdvertisementCampaignAudienceFilter) {
    invalidateCampaignResults(0, {
      query: appliedCampaignQuery,
      status: campaignStatusFilter,
      audience: value,
    });
    setOffset(0);
    setCampaignAudienceFilter(value);
  }

  function resetCampaignFilters() {
    const filters: NativeAdvertisementCampaignFilters = { query: "", status: "", audience: "" };
    invalidateCampaignResults(0, filters);
    setCampaignQuery("");
    setAppliedCampaignQuery("");
    setCampaignStatusFilter("");
    setCampaignAudienceFilter("");
    setOffset(0);
    if (!appliedCampaignQuery && !campaignStatusFilter && !campaignAudienceFilter && offset === 0) {
      void loadCampaigns(0, filters);
    }
  }

  function changeCampaignOffset(nextOffset: number) {
    invalidateCampaignResults(nextOffset, {
      query: appliedCampaignQuery,
      status: campaignStatusFilter,
      audience: campaignAudienceFilter,
    });
    setOffset(nextOffset);
  }

  function invalidateCampaignResults(nextOffset: number, filters: NativeAdvertisementCampaignFilters) {
    campaignRequestIdRef.current += 1;
    campaignViewRef.current = { offset: nextOffset, filters };
    setCampaigns([]);
    setTotalCount(0);
    setError(null);
    setLoading(true);
    setCampaignResultAnnouncement("");
  }

  return (
    <div className="app-updates-page advertising-page">
      {error ? <div className="error-banner inline-error" role="alert">{error}</div> : null}

      <section className="ad-page-intro" aria-labelledby="ad-page-title">
        <div>
          <p className="eyebrow">Campaign workspace</p>
          <h2 id="ad-page-title">Manage delivery, creative, and audience activity</h2>
          <p>Campaigns stay list-first. Open the editor only when you need to create or change one.</p>
        </div>
        <button className="primary-button ad-primary-action" onClick={beginCreating}>New campaign</button>
      </section>

      <PlacementPolicyPanel onUnauthorized={onUnauthorized} refreshKey={refreshKey} />

      <div className="ad-summary-grid" aria-label="Advertising summary">
        <SummaryCard label="Campaigns" value={totalCount.toLocaleString()} detail={`${campaigns.filter((item) => campaignStatus(item) === "ACTIVE").length} active on this page`} />
        <SummaryCard label="30d feed deliveries" value={pagePerformance.selections.toLocaleString()} detail="Server-added placements on this page" />
        <SummaryCard label="30d destination opens" value={pagePerformance.opens.toLocaleString()} detail={`${formatPercent(pagePerformance.rate)} open rate`} />
      </div>

      {editorOpen ? (
        <section ref={editorRef} className="app-update-panel ad-editor-panel" aria-labelledby="campaign-editor-title">
          <form noValidate onSubmit={(event) => void saveCampaign(event)}>
            <div className="panel-header app-update-heading ad-editor-heading">
              <div>
                <p className="eyebrow">{editingId === null ? "New campaign" : "Editing campaign"}</p>
                <h2 id="campaign-editor-title">{editingId === null ? "Create advertising campaign" : selected?.campaignKey ?? form.campaignKey}</h2>
                <p>Required fields are marked. Times use your local timezone and are stored as UTC.</p>
              </div>
              <span className={`status-pill ${formStatus(form) === "ACTIVE" ? "success" : ""}`}>{formStatus(form)}</span>
            </div>

            {attemptedSubmit && validationErrors.length > 0 ? (
              <div ref={validationRef} className="ad-validation-summary" role="alert" tabIndex={-1}>
                <strong>Review {validationErrors.length} field{validationErrors.length === 1 ? "" : "s"} before saving.</strong>
                <ul>{validationErrors.map((item) => <li key={item}>{item}</li>)}</ul>
              </div>
            ) : null}

            <fieldset className="ad-form-section">
              <legend>1. Campaign basics</legend>
              <p>Identify the campaign, choose its audience, and provide the allowlisted destination.</p>
              <div className="ad-basics-grid">
                <Field label="Campaign key" hint="Lowercase letters, numbers, and hyphens · 3–96 characters" required>
                  <input required value={form.campaignKey} placeholder="coupang-desk-lamp-august" onChange={(event) => update("campaignKey", event.target.value)} />
                </Field>
                <Field label="Audience" hint="Who can receive this campaign">
                  <select value={form.audience} onChange={(event) => update("audience", event.target.value as NativeAdvertisementCampaignInput["audience"])}>
                    <option value="ALL">All users</option>
                    <option value="AUTHENTICATED">Authenticated only</option>
                    <option value="ANONYMOUS">Anonymous only</option>
                  </select>
                </Field>
                <Field className="ad-field-span-2" label="Destination URL" hint="HTTPS link.coupang.com, coupang.com, or a supported BuddyStudy deep link" required>
                  <input required value={form.destinationUrl} placeholder="https://link.coupang.com/a/..." onChange={(event) => update("destinationUrl", event.target.value)} />
                </Field>
                <div className="app-update-field">
                  <span id="ad-delivery-state-label">Delivery state</span>
                  <label className="ad-checkbox">
                    <input type="checkbox" aria-labelledby="ad-delivery-state-label ad-delivery-state-option" checked={form.active} onChange={(event) => update("active", event.target.checked)} />
                    <span id="ad-delivery-state-option">Allow ranking and delivery</span>
                  </label>
                </div>
              </div>
            </fieldset>

            <fieldset className="ad-form-section">
              <legend>2. Localized creative</legend>
              <p>Keep the title concise. The optional body and required disclosure are shown in the user’s app language.</p>
              <div className="ad-locale-grid">
                <LocaleCard language="한국어" code="KO">
                  <Field label="Title" required><input required value={form.titleKo} onChange={(event) => update("titleKo", event.target.value)} /></Field>
                  <Field label="Body" hint="Optional"><textarea rows={3} value={form.bodyKo ?? ""} onChange={(event) => update("bodyKo", event.target.value || null)} /></Field>
                  <Field label="Disclosure" required><input required value={form.disclosureKo} onChange={(event) => update("disclosureKo", event.target.value)} /></Field>
                </LocaleCard>
                <LocaleCard language="English" code="EN">
                  <Field label="Title" required><input required value={form.titleEn} onChange={(event) => update("titleEn", event.target.value)} /></Field>
                  <Field label="Body" hint="Optional"><textarea rows={3} value={form.bodyEn ?? ""} onChange={(event) => update("bodyEn", event.target.value || null)} /></Field>
                  <Field label="Disclosure" required><input required value={form.disclosureEn} onChange={(event) => update("disclosureEn", event.target.value)} /></Field>
                </LocaleCard>
                <LocaleCard language="日本語" code="JA">
                  <Field label="Title" required><input required value={form.titleJa} onChange={(event) => update("titleJa", event.target.value)} /></Field>
                  <Field label="Body" hint="Optional"><textarea rows={3} value={form.bodyJa ?? ""} onChange={(event) => update("bodyJa", event.target.value || null)} /></Field>
                  <Field label="Disclosure" required><input required value={form.disclosureJa} onChange={(event) => update("disclosureJa", event.target.value)} /></Field>
                </LocaleCard>
              </div>
            </fieldset>

            <fieldset className="ad-form-section">
              <legend>3. Delivery and ranking</legend>
              <p>Ranking inputs use a 0–10 scale. Frequency rules are enforced separately for every historical user identity.</p>
              <div className="ad-rules-grid">
                <Field label="Base priority" hint="Strongest manual ranking input · 0–10"><NumberInput value={form.basePriority} max={10} step={0.1} onChange={(value) => update("basePriority", value)} /></Field>
                <Field label="Authenticated relevance" hint="0–10"><NumberInput value={form.authenticatedRelevance} max={10} step={0.1} onChange={(value) => update("authenticatedRelevance", value)} /></Field>
                <Field label="Anonymous relevance" hint="0–10"><NumberInput value={form.anonymousRelevance} max={10} step={0.1} onChange={(value) => update("anonymousRelevance", value)} /></Field>
                <Field label="Daily delivery cap" hint="Per user · 0 disables delivery"><NumberInput value={form.dailySelectionCap} max={100} onChange={(value) => update("dailySelectionCap", value)} /></Field>
                <Field label="Minimum repeat gap" hint="Hours per user"><NumberInput value={form.minimumSecondsBetweenSelections / 3600} max={720} onChange={(value) => update("minimumSecondsBetweenSelections", Math.round(value * 3600))} /></Field>
                <Field label="Cooldown after open" hint="Days per user"><NumberInput value={form.postViewCooldownSeconds / 86400} max={365} onChange={(value) => update("postViewCooldownSeconds", Math.round(value * 86400))} /></Field>
                <Field label="Minimum public items" hint="Feed must contain at least this many items"><NumberInput value={form.minimumFeedItemCount} min={1} max={100} onChange={(value) => update("minimumFeedItemCount", value)} /></Field>
                <div className="app-update-field" role="group" aria-labelledby="ad-position-range-label">
                  <span id="ad-position-range-label">Allowed position range</span>
                  <div className="ad-position-range"><NumberInput ariaLabel="Earliest allowed position" value={form.earliestPosition} max={99} onChange={(value) => update("earliestPosition", value)} /><span>to</span><NumberInput ariaLabel="Latest allowed position" value={form.latestPosition} max={99} onChange={(value) => update("latestPosition", value)} /></div>
                  <small>0-based unified feed index</small>
                </div>
              </div>
            </fieldset>

            <fieldset className="ad-form-section">
              <legend>4. Schedule</legend>
              <p>Leave both fields empty for an always-on campaign. End time must be later than start time.</p>
              <div className="ad-schedule-grid">
                <Field label="Starts at" hint="Optional · local time"><input type="datetime-local" value={toLocalDateTime(form.startsAt)} onChange={(event) => update("startsAt", toInstant(event.target.value))} /></Field>
                <Field label="Ends at" hint="Optional · local time"><input type="datetime-local" value={toLocalDateTime(form.endsAt)} onChange={(event) => update("endsAt", toInstant(event.target.value))} /></Field>
              </div>
            </fieldset>

            <div className="app-update-actions ad-editor-actions">
              <button type="button" className="secondary-button" disabled={saving} onClick={closeEditor}>Cancel</button>
              <button type="submit" className="primary-button" disabled={saving}>
                {saving ? "Saving…" : editingId === null ? "Create campaign" : "Save changes"}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      <section className="app-update-panel">
        <div className="panel-header app-update-heading ad-list-heading">
          <div><h2>Campaigns</h2><p>Performance uses the same 30-day selected cohort as server ranking.</p></div>
          <span>{totalCount.toLocaleString()} {hasCampaignFilters ? "matching" : "total"}</span>
        </div>
        <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{campaignResultAnnouncement}</p>
        <form className="ad-campaign-filters" role="search" aria-label="Filter advertising campaigns" onSubmit={applyCampaignSearch}>
          <div className="app-update-field ad-campaign-query-field">
            <label htmlFor="ad-campaign-query">Find campaign</label>
            <div className="ad-search-control">
              <input
                id="ad-campaign-query"
                value={campaignQuery}
                placeholder="Search campaigns"
                aria-describedby="ad-campaign-query-hint"
                disabled={saving}
                onChange={(event) => setCampaignQuery(event.target.value)}
              />
              <button className="secondary-button" type="submit" disabled={saving}>Search</button>
            </div>
            <small id="ad-campaign-query-hint">Campaign key or localized title</small>
          </div>
          <Field label="Status">
            <select disabled={saving} value={campaignStatusFilter} onChange={(event) => changeCampaignStatus(event.target.value as NativeAdvertisementCampaignStatusFilter)}>
              <option value="">All statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="PAUSED">Paused</option>
              <option value="SCHEDULED">Scheduled</option>
              <option value="ENDED">Ended</option>
            </select>
          </Field>
          <Field label="Audience">
            <select disabled={saving} value={campaignAudienceFilter} onChange={(event) => changeCampaignAudience(event.target.value as NativeAdvertisementCampaignAudienceFilter)}>
              <option value="">All audiences</option>
              <option value="ALL">All users</option>
              <option value="AUTHENTICATED">Members</option>
              <option value="ANONYMOUS">Anonymous</option>
            </select>
          </Field>
          <div className="ad-campaign-filter-actions">
            <button className="ghost-button" type="button" disabled={saving} onClick={resetCampaignFilters}>Reset filters</button>
          </div>
        </form>
        <div className="admin-table-scroll" role="region" aria-label="Advertising campaigns" aria-busy={loading} tabIndex={0}>
          <table className="admin-table ad-campaign-table">
            <thead><tr><th>Campaign</th><th>Status</th><th>Audience</th><th>30d feed deliveries</th><th>30d destination opens</th><th>Open rate</th><th>Schedule</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {campaigns.map((campaign) => (
                <tr key={campaign.id} className={editingId === campaign.id ? "selected" : ""}>
                  <td data-label="Campaign">
                    <strong>{campaign.titleKo}</strong>
                    <small>{campaign.campaignKey} · priority {campaign.basePriority.toFixed(1)}</small>
                  </td>
                  <td data-label="Status"><span className={`status-pill ${campaignStatus(campaign) === "ACTIVE" ? "success" : ""}`}>{campaignStatus(campaign)}</span></td>
                  <td data-label="Audience">{audienceLabel(campaign.audience)}</td>
                  <td data-label="30d feed deliveries"><strong>{campaign.performanceSelections.toLocaleString()}</strong></td>
                  <td data-label="30d destination opens"><strong>{campaign.performanceViews.toLocaleString()}</strong></td>
                  <td data-label="Open rate"><strong>{formatPercent(campaign.performanceViewRate)}</strong></td>
                  <td data-label="Schedule">{campaignSchedule(campaign)}</td>
                  <td className="ad-table-actions" data-label="Actions">
                    <button className="ghost-button compact" onClick={() => openAudience(campaign.id)}>Audience</button>
                    <button className="secondary-button compact" onClick={() => beginEditing(campaign)}>Edit</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading ? <p className="table-empty" role="status">{campaigns.length === 0 ? "Loading campaigns…" : "Updating campaign results…"}</p> : null}
          {!loading && campaigns.length === 0 ? (
            <p className="table-empty" role="status">
              {hasCampaignFilters
                ? "No campaigns match the current filters. Reset or adjust the search, status, or audience."
                : "No advertising campaigns yet. Create a campaign to get started."}
            </p>
          ) : null}
        </div>
        <div className="pager ad-pager">
          <button className="secondary-button" disabled={offset === 0 || loading} onClick={() => changeCampaignOffset(Math.max(0, offset - PAGE_SIZE))}>Previous</button>
          <span>{totalCount === 0 ? 0 : offset + 1}–{Math.min(totalCount, offset + campaigns.length)} of {totalCount}</span>
          <button className="secondary-button" disabled={offset + campaigns.length >= totalCount || loading} onClick={() => changeCampaignOffset(offset + PAGE_SIZE)}>Next</button>
        </div>
      </section>

      <AudienceActivity
        campaigns={campaigns}
        campaignId={activityCampaignId}
        onCampaignChange={setActivityCampaignId}
        onUnauthorized={onUnauthorized}
        refreshKey={refreshKey}
      />

      {rankingPolicy ? <RankingExplanation policy={rankingPolicy} /> : null}
    </div>
  );

  function update<Key extends keyof NativeAdvertisementCampaignInput>(key: Key, value: NativeAdvertisementCampaignInput[Key]) {
    setForm((current) => ({ ...current, [key]: value }));
  }
}

type PlacementPolicyLoadState = "LOADING" | "READY" | "ERROR";

function PlacementPolicyPanel({
  onUnauthorized,
  refreshKey,
}: {
  onUnauthorized: UnauthorizedHandler;
  refreshKey: number;
}) {
  const [policy, setPolicy] = useState<NativeAdPlacementPolicy | null>(null);
  const [form, setForm] = useState<NativeAdPlacementPolicyInput | null>(null);
  const [loadState, setLoadState] = useState<PlacementPolicyLoadState>("LOADING");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [attemptedSubmit, setAttemptedSubmit] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resultAnnouncement, setResultAnnouncement] = useState("");
  const requestIdRef = useRef(0);
  const validationRef = useRef<HTMLDivElement | null>(null);
  const validationErrors = form ? validatePlacementPolicy(form) : [];

  useEffect(() => {
    void loadPolicy();
    return () => {
      requestIdRef.current += 1;
    };
  }, [refreshKey]);

  async function loadPolicy() {
    const requestId = ++requestIdRef.current;
    setLoadState("LOADING");
    setLoadError(null);
    setPolicy(null);
    setForm(null);
    setAttemptedSubmit(false);
    setSaving(false);
    setResultAnnouncement("");
    try {
      const loaded = await fetchNativeAdPlacementPolicy(onUnauthorized);
      if (requestId !== requestIdRef.current) return;
      setPolicy(loaded);
      setForm(toPlacementPolicyInput(loaded));
      setLoadState("READY");
      setResultAnnouncement("Community feed placement policy loaded.");
    } catch (cause) {
      if (requestId !== requestIdRef.current) return;
      setLoadState("ERROR");
      setLoadError(message(cause));
    }
  }

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form || loadState !== "READY") return;
    setAttemptedSubmit(true);
    if (validationErrors.length > 0) {
      window.requestAnimationFrame(() => {
        validationRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
        validationRef.current?.focus({ preventScroll: true });
      });
      return;
    }

    const requestId = ++requestIdRef.current;
    setSaving(true);
    setLoadError(null);
    setResultAnnouncement("");
    try {
      const saved = await updateNativeAdPlacementPolicy(form, onUnauthorized);
      if (requestId !== requestIdRef.current) return;
      setPolicy(saved);
      setForm(toPlacementPolicyInput(saved));
      setAttemptedSubmit(false);
      setResultAnnouncement("Community feed placement policy saved.");
    } catch (cause) {
      if (requestId !== requestIdRef.current) return;
      setPolicy(null);
      setForm(null);
      setLoadState("ERROR");
      setLoadError(`The policy update could not be confirmed. ${message(cause)}`);
    } finally {
      if (requestId === requestIdRef.current) setSaving(false);
    }
  }

  function resetForm() {
    if (!policy || saving) return;
    setForm(toPlacementPolicyInput(policy));
    setAttemptedSubmit(false);
  }

  function update<Key extends keyof NativeAdPlacementPolicyInput>(
    key: Key,
    value: NativeAdPlacementPolicyInput[Key],
  ) {
    setForm((current) => current ? { ...current, [key]: value } : current);
  }

  const statusLabel = loadState !== "READY" ? "UNKNOWN" : saving ? "SAVING" : policy?.enabled ? "ON" : "OFF";

  return (
    <section className="app-update-panel ad-placement-panel" aria-labelledby="ad-placement-title" aria-busy={loadState === "LOADING" || saving}>
      <div className="panel-header app-update-heading ad-placement-heading">
        <div>
          <p className="eyebrow">Native placement policy</p>
          <h2 id="ad-placement-title">Community feed · COMMUNITY_FEED</h2>
          <p>Controls the shared AdMob-first slot before any fallback campaign is selected.</p>
        </div>
        <span className={`status-pill ${statusLabel === "ON" ? "success" : ""}`}>{statusLabel}</span>
      </div>
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{resultAnnouncement}</p>

      {loadState === "LOADING" ? (
        <div className="ad-policy-state" role="status">
          <strong>Checking policy state…</strong>
          <span>Editing stays locked until the server confirms the current values.</span>
        </div>
      ) : null}

      {loadState === "ERROR" ? (
        <div className="ad-policy-state error" role="alert">
          <div>
            <strong>Policy state is UNKNOWN.</strong>
            <span>{loadError ?? "The placement policy could not be loaded."} Controls remain locked so stale values cannot be saved.</span>
          </div>
          <button className="secondary-button" type="button" onClick={() => void loadPolicy()}>Retry</button>
        </div>
      ) : null}

      {loadState === "READY" && policy && form ? (
        <>
          <div className="ad-provider-metrics" aria-label="Community feed advertising metrics for the last 30 days">
            <ProviderMetricGroup
              title="Slot delivery"
              metrics={[["Slots delivered", policy.metrics.slotDeliveries]]}
            />
            <ProviderMetricGroup
              title="AdMob"
              metrics={[
                ["Impressions", policy.metrics.adMobImpressions],
                ["Clicks", policy.metrics.adMobClicks],
              ]}
            />
            <ProviderMetricGroup
              title="Fallback campaigns"
              metrics={[
                ["Selections", policy.metrics.fallbackSelections],
                ["Impressions", policy.metrics.fallbackImpressions],
                ["Destination opens", policy.metrics.fallbackOpens],
              ]}
            />
          </div>

          <form className="ad-placement-form" noValidate onSubmit={(event) => void savePolicy(event)}>
            {attemptedSubmit && validationErrors.length > 0 ? (
              <div ref={validationRef} className="ad-validation-summary" role="alert" tabIndex={-1}>
                <strong>Review {validationErrors.length} policy field{validationErrors.length === 1 ? "" : "s"} before saving.</strong>
                <ul>{validationErrors.map((item) => <li key={item}>{item}</li>)}</ul>
              </div>
            ) : null}

            <fieldset className="ad-form-section" disabled={saving}>
              <legend>Delivery state and period</legend>
              <p>The initial rollout stays OFF. Optional times use your local timezone and are stored as UTC.</p>
              <div className="ad-policy-state-grid">
                <div className="app-update-field">
                  <span id="ad-placement-enabled-label">Shared slot delivery</span>
                  <label className="ad-checkbox">
                    <input
                      type="checkbox"
                      aria-labelledby="ad-placement-enabled-label ad-placement-enabled-option"
                      checked={form.enabled}
                      onChange={(event) => update("enabled", event.target.checked)}
                    />
                    <span id="ad-placement-enabled-option">Enable COMMUNITY_FEED slots</span>
                  </label>
                  <small>Paid ad-free users and uncertain entitlements remain ineligible on the server.</small>
                </div>
                <Field label="Starts at" hint="Optional · local time">
                  <input type="datetime-local" value={toLocalDateTime(form.startsAt)} onChange={(event) => update("startsAt", toInstant(event.target.value))} />
                </Field>
                <Field label="Ends at" hint="Optional · local time">
                  <input type="datetime-local" value={toLocalDateTime(form.endsAt)} onChange={(event) => update("endsAt", toInstant(event.target.value))} />
                </Field>
              </div>
            </fieldset>

            <fieldset className="ad-form-section" disabled={saving}>
              <legend>Frequency, feed size, and position</legend>
              <p>All positions are 0-based unified-feed indexes. The server still forces every slot after two questions and before the final question.</p>
              <div className="ad-policy-rules-grid">
                <Field label="Daily delivery cap" hint="Per user, per UTC day · 0 disables delivery">
                  <NumberInput value={form.dailyDeliveryCap} max={100} onChange={(value) => update("dailyDeliveryCap", value)} />
                </Field>
                <Field label="Minimum repeat gap" hint="Seconds per user · minimum 60">
                  <NumberInput value={form.minimumSecondsBetweenDeliveries} min={60} max={2_592_000} onChange={(value) => update("minimumSecondsBetweenDeliveries", value)} />
                </Field>
                <Field label="Minimum public questions" hint="At least 4 questions">
                  <NumberInput value={form.minimumFeedItemCount} min={4} max={100} onChange={(value) => update("minimumFeedItemCount", value)} />
                </Field>
                <div className="app-update-field" role="group" aria-labelledby="ad-policy-position-range-label">
                  <span id="ad-policy-position-range-label">Allowed position range</span>
                  <div className="ad-position-range">
                    <NumberInput ariaLabel="Earliest slot position" value={form.earliestPosition} min={2} max={99} onChange={(value) => update("earliestPosition", value)} />
                    <span>to</span>
                    <NumberInput ariaLabel="Latest slot position" value={form.latestPosition} min={2} max={99} onChange={(value) => update("latestPosition", value)} />
                  </div>
                  <small>Earliest 2 · latest must be within 2–99 and not precede earliest</small>
                </div>
              </div>
            </fieldset>

            <div className="ad-placement-footer">
              <small>Last confirmed update: <time dateTime={policy.updatedAt}>{formatDate(policy.updatedAt)}</time></small>
              <div className="app-update-actions">
                <button className="secondary-button" type="button" disabled={saving} onClick={resetForm}>Reset</button>
                <button className="primary-button" type="submit" disabled={saving}>{saving ? "Saving…" : "Save placement policy"}</button>
              </div>
            </div>
          </form>
        </>
      ) : null}
    </section>
  );
}

function AudienceActivity({
  campaigns,
  campaignId,
  onCampaignChange,
  onUnauthorized,
  refreshKey,
}: {
  campaigns: NativeAdvertisementCampaignSummary[];
  campaignId: number | null;
  onCampaignChange: (campaignId: number) => void;
  onUnauthorized: UnauthorizedHandler;
  refreshKey: number;
}) {
  const [page, setPage] = useState<NativeAdvertisementUserPage>(emptyUserPage);
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [status, setStatus] = useState<NativeAdvertisementUserStatusFilter>("");
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadedViewKey, setLoadedViewKey] = useState("");
  const [userResultAnnouncement, setUserResultAnnouncement] = useState("");
  const previousCampaignId = useRef<number | null>(null);
  const userRequestIdRef = useRef(0);
  const selected = campaigns.find((campaign) => campaign.id === campaignId) ?? null;
  const currentViewKey = userViewKey(campaignId, offset, appliedQuery, status, refreshKey);
  const pageIsCurrent = loadedViewKey === currentViewKey;
  const visiblePage = pageIsCurrent ? page : emptyUserPage;
  const activityLoading = campaignId !== null && (loading || !pageIsCurrent);

  useEffect(() => {
    if (campaignId === null) {
      userRequestIdRef.current += 1;
      previousCampaignId.current = null;
      setPage(emptyUserPage);
      setLoadedViewKey("");
      setLoading(false);
      setUserResultAnnouncement("");
      return;
    }
    const campaignChanged = previousCampaignId.current !== campaignId;
    previousCampaignId.current = campaignId;
    if (campaignChanged) invalidateUserResults();
    if (campaignChanged && offset !== 0) {
      setOffset(0);
      return;
    }
    void loadUsers(campaignId, offset, appliedQuery, status, currentViewKey);
  }, [campaignId, appliedQuery, status, offset, refreshKey]);

  async function loadUsers(
    selectedCampaignId: number,
    nextOffset: number,
    nextQuery: string,
    nextStatus: NativeAdvertisementUserStatusFilter,
    viewKey: string,
  ) {
    const requestId = ++userRequestIdRef.current;
    setLoading(true);
    setError(null);
    setUserResultAnnouncement("");
    try {
      const nextPage = await fetchNativeAdvertisementCampaignUsers(selectedCampaignId, onUnauthorized, PAGE_SIZE, nextOffset, nextQuery, nextStatus);
      if (requestId !== userRequestIdRef.current) return;
      setPage(nextPage);
      setLoadedViewKey(viewKey);
      setUserResultAnnouncement(`${nextPage.totalCount.toLocaleString()} audience activity results loaded.`);
    } catch (cause) {
      if (requestId !== userRequestIdRef.current) return;
      setLoadedViewKey(viewKey);
      setError(message(cause));
    } finally {
      if (requestId === userRequestIdRef.current) setLoading(false);
    }
  }

  function selectCampaign(value: string) {
    invalidateUserResults();
    setOffset(0);
    onCampaignChange(Number(value));
  }

  function applySearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextQuery = query.trim();
    invalidateUserResults();
    setOffset(0);
    setAppliedQuery(nextQuery);
    if (nextQuery === appliedQuery && offset === 0 && campaignId !== null) {
      const viewKey = userViewKey(campaignId, 0, nextQuery, status, refreshKey);
      void loadUsers(campaignId, 0, nextQuery, status, viewKey);
    }
  }

  function changeStatus(nextStatus: NativeAdvertisementUserStatusFilter) {
    invalidateUserResults();
    setStatus(nextStatus);
    setOffset(0);
  }

  function changeUserOffset(nextOffset: number) {
    invalidateUserResults();
    setOffset(nextOffset);
  }

  function invalidateUserResults() {
    userRequestIdRef.current += 1;
    setPage(emptyUserPage);
    setLoadedViewKey("");
    setError(null);
    setLoading(true);
    setUserResultAnnouncement("");
  }

  return (
    <section id="ad-audience-activity" className="app-update-panel ad-activity-panel" aria-labelledby="ad-activity-title">
      <div className="panel-header app-update-heading ad-list-heading">
        <div>
          <h2 id="ad-activity-title">Audience activity</h2>
          <p>Feed deliveries are server-added placements. Destination opens are idempotent ad-tap events, not proof that the external page loaded.</p>
        </div>
        <span>{visiblePage.totalCount.toLocaleString()} users</span>
      </div>
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{userResultAnnouncement}</p>

      <div className="ad-activity-toolbar">
        <Field label="Campaign">
          <select value={campaignId ?? ""} disabled={campaigns.length === 0} onChange={(event) => selectCampaign(event.target.value)}>
            {campaigns.length === 0 ? <option value="">No campaigns</option> : null}
            {campaigns.map((campaign) => <option key={campaign.id} value={campaign.id}>{campaign.titleKo} · {campaign.campaignKey}</option>)}
          </select>
        </Field>
        <form className="ad-user-search" role="search" onSubmit={applySearch}>
          <div className="app-update-field">
            <label htmlFor="ad-user-search-input">Find user</label>
            <div className="ad-search-control">
              <input id="ad-user-search-input" aria-describedby="ad-user-search-hint" value={query} placeholder="Search users" onChange={(event) => setQuery(event.target.value)} />
              <button className="secondary-button" type="submit">Search</button>
            </div>
            <small id="ad-user-search-hint">Email, display name, or exact user ID</small>
          </div>
        </form>
        <Field label="Open status">
          <select value={status} onChange={(event) => changeStatus(event.target.value as NativeAdvertisementUserStatusFilter)}>
            <option value="">All delivery activity</option>
            <option value="OPENED">Opened destination</option>
            <option value="NOT_OPENED">No destination open</option>
          </select>
        </Field>
      </div>

      {selected ? <p className="ad-activity-context"><strong>{selected.titleKo}</strong><span>{selected.campaignKey}</span></p> : null}
      {error ? <div className="ad-validation-summary" role="alert">{error}</div> : null}

      <div className="admin-table-scroll" role="region" aria-label="Campaign audience activity" aria-busy={activityLoading} tabIndex={0}>
        <table className="admin-table ad-user-table">
          <thead><tr><th>User</th><th>Feed deliveries</th><th>Destination opens</th><th>Open rate</th><th>Devices</th><th>First delivery</th><th>Latest delivery</th><th>Latest open</th></tr></thead>
          <tbody>
            {visiblePage.users.map((user) => (
              <tr key={user.userId}>
                <td data-label="User"><UserIdentity user={user} /></td>
                <td data-label="Feed deliveries"><strong>{user.selectionCount.toLocaleString()}</strong></td>
                <td data-label="Destination opens"><strong>{user.destinationOpenCount.toLocaleString()}</strong></td>
                <td data-label="Open rate"><strong>{formatPercent(user.openRate)}</strong></td>
                <td data-label="Devices">{user.distinctDeviceCount.toLocaleString()}</td>
                <td data-label="First delivery">{formatDate(user.firstSelectedAt)}</td>
                <td data-label="Latest delivery">{formatDate(user.lastSelectedAt)}</td>
                <td data-label="Latest open">{user.lastViewedAt ? formatDate(user.lastViewedAt) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {activityLoading ? <p className="table-empty" role="status">Loading audience activity…</p> : null}
        {!activityLoading && campaignId !== null && visiblePage.users.length === 0 ? <p className="table-empty">No users match these filters.</p> : null}
        {!activityLoading && campaignId === null ? <p className="table-empty">Create a campaign to inspect audience activity.</p> : null}
      </div>
      <div className="pager ad-pager">
        <button className="secondary-button" disabled={offset === 0 || activityLoading} onClick={() => changeUserOffset(Math.max(0, offset - PAGE_SIZE))}>Previous</button>
        <span>{visiblePage.totalCount === 0 ? 0 : offset + 1}–{Math.min(visiblePage.totalCount, offset + visiblePage.users.length)} of {visiblePage.totalCount}</span>
        <button className="secondary-button" disabled={offset + visiblePage.users.length >= visiblePage.totalCount || activityLoading} onClick={() => changeUserOffset(offset + PAGE_SIZE)}>Next</button>
      </div>
      <p className="ad-privacy-note">Anonymous rows are historical installation identities and are not linked to a member who signs in later. Raw device identifiers are not shown.</p>
    </section>
  );
}

function userViewKey(
  campaignId: number | null,
  offset: number,
  query: string,
  status: NativeAdvertisementUserStatusFilter,
  refreshKey: number,
) {
  return JSON.stringify([campaignId, offset, query, status, refreshKey]);
}

function UserIdentity({ user }: { user: NativeAdvertisementUserSummary }) {
  if (user.accountStatus === "ACTIVE" || user.accountStatus === "PENDING_TERMS") {
    const suffix = user.accountStatus === "PENDING_TERMS" ? " · terms pending" : "";
    return <><strong>{user.displayName?.trim() || `User #${user.userId}`}</strong><small>{user.email?.trim() || `User #${user.userId}`}{suffix}</small></>;
  }
  if (user.accountStatus === "WITHDRAWN") {
    return <><strong>Withdrawn user</strong><small>Historical user #{user.userId}</small></>;
  }
  return <><strong>Anonymous installation</strong><small>Historical user #{user.userId}</small></>;
}

function SummaryCard({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <article className="ad-summary-card"><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>;
}

function ProviderMetricGroup({
  title,
  metrics,
}: {
  title: string;
  metrics: ReadonlyArray<readonly [label: string, value: number]>;
}) {
  return (
    <section className="ad-provider-metric-group">
      <h3>{title}</h3>
      <dl>
        {metrics.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value.toLocaleString()}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function LocaleCard({ language, code, children }: { language: string; code: string; children: ReactNode }) {
  return <section className="ad-locale-card"><header><strong>{language}</strong><span>{code}</span></header>{children}</section>;
}

function RankingExplanation({ policy }: { policy: NativeAdvertisementRankingPolicy }) {
  return (
    <details className="app-update-panel ad-ranking-panel">
      <summary>
        <span><strong>How server ranking works</strong><small>Eligibility, ranking weights, and exploration policy</small></span>
        <b>{policy.exploitationPercent}% top-ranked · {policy.explorationPercent}% explore</b>
      </summary>
      <div className="ad-ranking-formula">
        <p>The app preserves server order. Campaigns first pass schedule, audience, frequency, destination, and feed-size checks.</p>
        <strong>score = priority×{policy.basePriorityWeight} + audience relevance×{policy.relevanceWeight} + smoothed open rate×{policy.smoothedViewRateWeight} + exploration×{policy.explorationWeight} + freshness×{policy.freshnessWeight} − today deliveries×{policy.dailySelectionPenalty}</strong>
        <p>The server normally uses rank #1 and explores rank #2–#{policy.selectionPoolSize} {policy.explorationPercent}% of the time. Position is randomized only within the campaign’s allowed range.</p>
      </div>
    </details>
  );
}

function Field({ label, hint, required = false, className = "", children }: { label: string; hint?: string; required?: boolean; className?: string; children: ReactNode }) {
  return <label className={`app-update-field ${className}`.trim()}><span>{label}{required ? <b className="required-mark" aria-hidden="true"> *</b> : null}</span>{children}{hint ? <small>{hint}</small> : null}</label>;
}

function NumberInput({ ariaLabel, value, min = 0, max, step = 1, onChange }: { ariaLabel?: string; value: number; min?: number; max?: number; step?: number; onChange: (value: number) => void }) {
  return <input type="number" aria-label={ariaLabel} min={min} max={max} step={step} value={value} onChange={(event) => onChange(Number(event.target.value))} />;
}

function validateForm(form: NativeAdvertisementCampaignInput): string[] {
  const errors: string[] = [];
  if (!/^[a-z0-9][a-z0-9-]{2,95}$/.test(form.campaignKey.trim())) errors.push("Campaign key must use 3–96 lowercase letters, numbers, or hyphens.");
  if (!form.destinationUrl.trim()) errors.push("Destination URL is required.");
  else if (form.destinationUrl.trim().length > 512) errors.push("Destination URL must be 512 characters or fewer.");
  else if (!isSupportedDestination(form.destinationUrl.trim())) errors.push("Destination must be a supported BuddyStudy deep link or HTTPS Coupang URL.");
  if (!form.titleKo.trim() || !form.titleEn.trim() || !form.titleJa.trim()) errors.push("A title is required in Korean, English, and Japanese.");
  else if ([form.titleKo, form.titleEn, form.titleJa].some((value) => value.trim().length > 255)) errors.push("Advertising titles must be 255 characters or fewer.");
  if (!form.disclosureKo.trim() || !form.disclosureEn.trim() || !form.disclosureJa.trim()) errors.push("An advertising disclosure is required in every language.");
  else if ([form.disclosureKo, form.disclosureEn, form.disclosureJa].some((value) => value.trim().length > 32)) errors.push("Advertising disclosures must be 32 characters or fewer.");
  if ([form.bodyKo, form.bodyEn, form.bodyJa].some((value) => (value?.trim().length ?? 0) > 500)) errors.push("Advertising body copy must be 500 characters or fewer.");
  if (![form.basePriority, form.authenticatedRelevance, form.anonymousRelevance].every((value) => Number.isFinite(value) && value >= 0 && value <= 10)) errors.push("Ranking values must be between 0 and 10.");
  if (!integerInRange(form.dailySelectionCap, 0, 100)) errors.push("Daily delivery cap must be between 0 and 100.");
  if (!integerInRange(form.minimumSecondsBetweenSelections, 0, 2_592_000)) errors.push("Minimum repeat gap must be between 0 and 720 hours.");
  if (!integerInRange(form.postViewCooldownSeconds, 0, 31_536_000)) errors.push("Cooldown after open must be between 0 and 365 days.");
  if (!integerInRange(form.minimumFeedItemCount, 1, 100)) errors.push("Minimum public items must be between 1 and 100.");
  if (!integerInRange(form.earliestPosition, 0, 99) || !integerInRange(form.latestPosition, 0, 99)) errors.push("Allowed positions must be whole numbers between 0 and 99.");
  else if (form.latestPosition < form.earliestPosition) errors.push("Latest position must be equal to or later than earliest position.");
  if (form.startsAt && form.endsAt && new Date(form.endsAt) <= new Date(form.startsAt)) errors.push("Campaign end time must be later than its start time.");
  return errors;
}

function validatePlacementPolicy(form: NativeAdPlacementPolicyInput): string[] {
  const errors: string[] = [];
  if (form.placement !== "COMMUNITY_FEED") errors.push("Placement must remain COMMUNITY_FEED.");
  if (!integerInRange(form.dailyDeliveryCap, 0, 100)) errors.push("Daily delivery cap must be a whole number between 0 and 100.");
  if (!integerInRange(form.minimumSecondsBetweenDeliveries, 60, 2_592_000)) errors.push("Minimum repeat gap must be a whole number between 60 and 2,592,000 seconds.");
  if (!integerInRange(form.minimumFeedItemCount, 4, 100)) errors.push("Minimum public questions must be a whole number between 4 and 100.");
  if (!integerInRange(form.earliestPosition, 2, 99) || !integerInRange(form.latestPosition, 2, 99)) {
    errors.push("Allowed positions must be whole numbers between 2 and 99.");
  } else if (form.latestPosition < form.earliestPosition) {
    errors.push("Latest slot position must be equal to or later than earliest slot position.");
  }
  if (form.startsAt && form.endsAt && new Date(form.endsAt) <= new Date(form.startsAt)) {
    errors.push("Policy end time must be later than its start time.");
  }
  return errors;
}

function integerInRange(value: number, min: number, max: number) {
  return Number.isInteger(value) && value >= min && value <= max;
}

function isSupportedDestination(value: string) {
  try {
    const url = new URL(value);
    if (url.username || url.password || url.hash) return false;
    if (url.protocol === "buddystudy:") {
      return SUPPORTED_BUDDYSTUDY_DESTINATIONS.has(url.hostname.toLowerCase()) && !url.port;
    }
    return url.protocol === "https:"
      && SUPPORTED_COUPANG_HOSTS.has(url.hostname.toLowerCase())
      && (!url.port || url.port === "443")
      && Boolean(url.pathname);
  } catch {
    return false;
  }
}

const SUPPORTED_BUDDYSTUDY_DESTINATIONS = new Set(["home", "study", "studies", "records", "record", "history", "stats", "statistics", "settings", "profile", "public", "feedback"]);
const SUPPORTED_COUPANG_HOSTS = new Set(["coupang.com", "www.coupang.com", "link.coupang.com"]);

function toInput(campaign: NativeAdvertisementCampaignSummary): NativeAdvertisementCampaignInput {
  const { id: _id, placement: _placement, performanceSelections: _selections, performanceViews: _views, performanceViewRate: _rate, createdAt: _created, updatedAt: _updated, ...input } = campaign;
  return input;
}

function toPlacementPolicyInput(policy: NativeAdPlacementPolicy): NativeAdPlacementPolicyInput {
  const { updatedAt: _updatedAt, metrics: _metrics, ...input } = policy;
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
  return statusFromValues(campaign.active, campaign.startsAt, campaign.endsAt);
}

function formStatus(form: NativeAdvertisementCampaignInput) {
  return statusFromValues(form.active, form.startsAt, form.endsAt);
}

function statusFromValues(active: boolean, startsAt: string | null, endsAt: string | null) {
  if (!active) return "PAUSED";
  const now = Date.now();
  if (startsAt && new Date(startsAt).getTime() > now) return "SCHEDULED";
  if (endsAt && new Date(endsAt).getTime() <= now) return "ENDED";
  return "ACTIVE";
}

function campaignSchedule(campaign: NativeAdvertisementCampaignSummary) {
  if (!campaign.startsAt && !campaign.endsAt) return "Always on";
  if (campaign.startsAt && campaign.endsAt) return `${formatShortDate(campaign.startsAt)} – ${formatShortDate(campaign.endsAt)}`;
  if (campaign.startsAt) return `From ${formatShortDate(campaign.startsAt)}`;
  return `Until ${formatShortDate(campaign.endsAt!)}`;
}

function audienceLabel(audience: NativeAdvertisementCampaignInput["audience"]) {
  if (audience === "AUTHENTICATED") return "Members";
  if (audience === "ANONYMOUS") return "Anonymous";
  return "All users";
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatShortDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(new Date(value));
}

function message(cause: unknown) {
  return cause instanceof Error ? cause.message : "Request failed";
}
