import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Plus, RefreshCw } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import {
  DataTable,
  DetailDrawer,
  PageHeader,
  Pagination,
  SearchField,
  StatusBadge,
} from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { formatDateTime } from "../lib/format.js";

const PAGE_SIZE = 20;
const DEFAULT_FORM = {
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
  imageUrl: "",
  affiliateDisclosureKo: "이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.",
  affiliateDisclosureEn: "This content contains Coupang Partners affiliate links, and we may receive a commission from qualifying purchases.",
  affiliateDisclosureJa: "このコンテンツはCoupang Partnersの活動の一環として、購入により一定額の手数料を受け取る場合があります。",
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

function campaignStatus(campaign) {
  if (!campaign.active) return "PAUSED";
  const now = Date.now();
  if (campaign.startsAt && new Date(campaign.startsAt).getTime() > now) return "SCHEDULED";
  if (campaign.endsAt && new Date(campaign.endsAt).getTime() <= now) return "ENDED";
  return "ACTIVE";
}

function statusTone(status) {
  if (status === "ACTIVE") return "success";
  if (status === "SCHEDULED") return "info";
  if (status === "ENDED") return "neutral";
  return "warning";
}

function percentage(value) {
  return `${(Number(value || 0) * 100).toFixed(1)}%`;
}

function localDateTime(value) {
  if (!value) return "";
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function instant(value) {
  return value ? new Date(value).toISOString() : null;
}

function campaignSchedule(campaign) {
  if (!campaign.startsAt && !campaign.endsAt) return "Always on";
  if (campaign.startsAt && campaign.endsAt) {
    return `${formatDateTime(campaign.startsAt)} – ${formatDateTime(campaign.endsAt)}`;
  }
  if (campaign.startsAt) return `Starts ${formatDateTime(campaign.startsAt)}`;
  return `Ends ${formatDateTime(campaign.endsAt)}`;
}

function inputFromCampaign(campaign) {
  return Object.fromEntries(Object.keys(DEFAULT_FORM).map((key) => [key, campaign[key] ?? DEFAULT_FORM[key]]));
}

function validate(form) {
  const errors = [];
  if (!/^[a-z0-9][a-z0-9-]{2,95}$/.test(form.campaignKey.trim())) {
    errors.push("Campaign key must use 3–96 lowercase letters, numbers, or hyphens.");
  }
  if (!form.destinationUrl.trim()) errors.push("Coupang destination URL is required.");
  else {
    try {
      const url = new URL(form.destinationUrl.trim());
      const allowedHosts = new Set(["coupang.com", "www.coupang.com", "link.coupang.com"]);
      const supportedAppRoutes = new Set(["home", "study", "studies", "records", "record", "history", "stats", "statistics", "settings", "profile", "public", "feedback"]);
      const validCoupang = url.protocol === "https:" && allowedHosts.has(url.hostname.toLowerCase()) && Boolean(url.pathname);
      const validAppRoute = url.protocol === "buddystudy:" && supportedAppRoutes.has(url.hostname.toLowerCase());
      if (!validCoupang && !validAppRoute) {
        errors.push("Destination must be an HTTPS Coupang URL or a supported BuddyStudy deep link.");
      } else if (validCoupang) {
        if (!form.imageUrl?.trim()) errors.push("A Coupang product image URL is required.");
        if ([form.affiliateDisclosureKo, form.affiliateDisclosureEn, form.affiliateDisclosureJa]
          .some((value) => !value?.trim())) {
          errors.push("Affiliate disclosure is required in every language.");
        }
      }
    } catch {
      errors.push("Destination URL is not valid.");
    }
  }
  if (form.imageUrl?.trim()) {
    try {
      const image = new URL(form.imageUrl.trim());
      const host = image.hostname.toLowerCase();
      if (image.protocol !== "https:" || (host !== "coupangcdn.com" && !host.endsWith(".coupangcdn.com"))) {
        errors.push("Product image must use an HTTPS coupangcdn.com URL.");
      }
    } catch {
      errors.push("Product image URL is not valid.");
    }
  }
  if ([form.titleKo, form.titleEn, form.titleJa].some((value) => !value.trim())) {
    errors.push("Korean, English, and Japanese titles are required.");
  }
  if ([form.disclosureKo, form.disclosureEn, form.disclosureJa].some((value) => !value.trim())) {
    errors.push("Advertising disclosure is required in every language.");
  }
  if ([form.basePriority, form.authenticatedRelevance, form.anonymousRelevance]
    .some((value) => !Number.isFinite(value) || value < 0 || value > 10)) {
    errors.push("Ranking values must be between 0 and 10.");
  }
  if (!Number.isInteger(form.dailySelectionCap) || form.dailySelectionCap < 0 || form.dailySelectionCap > 100) {
    errors.push("Daily delivery cap must be between 0 and 100.");
  }
  if (form.latestPosition < form.earliestPosition) {
    errors.push("Latest feed position must be greater than or equal to earliest position.");
  }
  if (form.startsAt && form.endsAt && new Date(form.endsAt) <= new Date(form.startsAt)) {
    errors.push("Campaign end time must be later than its start time.");
  }
  return errors;
}

function NumberField({ label, hint, value, onChange, min = 0, max, step = 1 }) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        type="number"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
      {hint ? <small>{hint}</small> : null}
    </label>
  );
}

function CampaignAudienceActivity({ campaign }) {
  const [offset, setOffset] = useState(0);
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const activityQuery = useQuery({
    queryKey: ["admin", "native-ad-campaign-users", campaign.id, query, status, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (query) params.set("query", query);
      if (status) params.set("status", status);
      return adminFetch(`/native-ad-campaigns/${campaign.id}/users?${params}`);
    },
    placeholderData: keepPreviousData,
  });
  const users = Array.isArray(activityQuery.data?.users) ? activityQuery.data.users : [];
  const total = Number(activityQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "user",
      label: "User",
      render: (row) => (
        <div className="primary-cell">
          <strong>{row.displayName || (row.accountStatus === "ANONYMOUS" ? "Anonymous installation" : `User ${row.userId}`)}</strong>
          <span>{row.email || `${row.accountStatus} · #${row.userId}`}</span>
        </div>
      ),
    },
    { key: "deliveries", label: "Deliveries", render: (row) => Number(row.selectionCount || 0).toLocaleString() },
    { key: "impressions", label: "Seen", render: (row) => Number(row.impressionCount || 0).toLocaleString() },
    { key: "opens", label: "Opens", render: (row) => Number(row.destinationOpenCount || 0).toLocaleString() },
    { key: "rate", label: "Open after seen", render: (row) => percentage(row.viewableOpenRate) },
    { key: "devices", label: "Devices", render: (row) => Number(row.distinctDeviceCount || 0).toLocaleString() },
    { key: "latest", label: "Latest delivery", render: (row) => formatDateTime(row.lastSelectedAt) },
    { key: "opened", label: "Latest open", render: (row) => row.lastViewedAt ? formatDateTime(row.lastViewedAt) : "—" },
  ], []);

  return (
    <section className="drawer-section advertising-audience-section">
      <h3>Audience activity</h3>
      <p className="section-description">Deliveries, ads actually seen for at least one second, and destination opens. Anonymous device identifiers stay hidden.</p>
      <div className="advertising-audience-toolbar">
        <SearchField
          value={queryDraft}
          onChange={setQueryDraft}
          onSubmit={() => {
            setOffset(0);
            setQuery(queryDraft.trim());
          }}
          label="Advertising audience search"
          placeholder="Email, name, or user ID"
        />
        <label className="field compact-field">
          <span>Open state</span>
          <select value={status} onChange={(event) => { setOffset(0); setStatus(event.target.value); }}>
            <option value="">All</option>
            <option value="OPENED">Opened</option>
            <option value="NOT_OPENED">Not opened</option>
          </select>
        </label>
      </div>
      {activityQuery.error ? <InlineNotice tone="danger">{activityQuery.error.message}</InlineNotice> : null}
      <DataTable
        columns={columns}
        rows={users}
        rowKey={(row) => row.userId}
        emptyText="No audience activity matches these filters."
        loading={activityQuery.isLoading || activityQuery.isPlaceholderData}
      />
      <Pagination
        page={page}
        totalPages={totalPages}
        label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + users.length, total)} of ${total}` : "0 users"}
        onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
        onNext={() => setOffset(offset + PAGE_SIZE)}
        fetching={activityQuery.isFetching}
      />
    </section>
  );
}

function CampaignEditor({ campaign, onClose, onSaved }) {
  const [form, setForm] = useState(() => campaign ? inputFromCampaign(campaign) : { ...DEFAULT_FORM });
  const [attempted, setAttempted] = useState(false);
  const errors = validate(form);
  const mutation = useMutation({
    mutationFn: () => adminFetch(
      campaign ? `/native-ad-campaigns/${campaign.id}` : "/native-ad-campaigns",
      {
        method: campaign ? "PUT" : "POST",
        body: JSON.stringify({
          ...form,
          campaignKey: form.campaignKey.trim(),
          destinationUrl: form.destinationUrl.trim(),
          imageUrl: form.imageUrl?.trim() || null,
          bodyKo: form.bodyKo?.trim() || null,
          bodyEn: form.bodyEn?.trim() || null,
          bodyJa: form.bodyJa?.trim() || null,
          affiliateDisclosureKo: form.affiliateDisclosureKo?.trim() || null,
          affiliateDisclosureEn: form.affiliateDisclosureEn?.trim() || null,
          affiliateDisclosureJa: form.affiliateDisclosureJa?.trim() || null,
        }),
      },
    ),
    onSuccess: (saved) => onSaved(saved),
  });

  function update(key, value) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function submit(event) {
    event.preventDefault();
    setAttempted(true);
    if (errors.length === 0) mutation.mutate();
  }

  return (
    <DetailDrawer
      open
      width="wide"
      title={campaign ? `Edit ${campaign.campaignKey}` : "Create advertising campaign"}
      subtitle="Coupang creative, audience, ranking, and feed delivery rules"
      onClose={onClose}
    >
      <form className="advertising-editor" onSubmit={submit} noValidate>
        <section className="drawer-section">
          <h3>Campaign basics</h3>
          <div className="form-grid">
            <label className="field">
              <span>Campaign key</span>
              <input
                required
                value={form.campaignKey}
                placeholder="coupang-desk-lamp-august"
                onChange={(event) => update("campaignKey", event.target.value)}
              />
              <small>Lowercase letters, numbers, and hyphens</small>
            </label>
            <label className="field">
              <span>Audience</span>
              <select value={form.audience} onChange={(event) => update("audience", event.target.value)}>
                <option value="ALL">All users</option>
                <option value="AUTHENTICATED">Signed-in users</option>
                <option value="ANONYMOUS">Anonymous users</option>
              </select>
            </label>
            <label className="field advertising-wide-field">
              <span>Coupang destination URL</span>
              <input
                required
                value={form.destinationUrl}
                placeholder="https://link.coupang.com/a/..."
                onChange={(event) => update("destinationUrl", event.target.value)}
              />
              <small>The app receives this URL with the ranked advertisement and opens it on tap.</small>
            </label>
            <label className="field advertising-wide-field">
              <span>Coupang product image URL</span>
              <input
                value={form.imageUrl || ""}
                placeholder="https://thumbnail.coupangcdn.com/..."
                onChange={(event) => update("imageUrl", event.target.value)}
              />
              <small>Use the HTTPS coupangcdn.com image URL supplied with the product creative.</small>
            </label>
            {form.imageUrl ? (
              <div className="advertising-image-preview">
                <img src={form.imageUrl} alt="Coupang campaign preview" />
                <span>The iOS feed shows this image beside the advertisement copy.</span>
              </div>
            ) : null}
            <label className="advertising-toggle">
              <input type="checkbox" checked={form.active} onChange={(event) => update("active", event.target.checked)} />
              <span><strong>Campaign active</strong><small>Eligible for server ranking and feed delivery</small></span>
            </label>
          </div>
        </section>

        <section className="drawer-section">
          <h3>Localized creative</h3>
          <div className="advertising-language-grid">
            {[
              ["Korean", "Ko"],
              ["English", "En"],
              ["Japanese", "Ja"],
            ].map(([language, suffix]) => (
              <fieldset className="advertising-language" key={suffix}>
                <legend>{language}</legend>
                <label className="field">
                  <span>Title</span>
                  <input value={form[`title${suffix}`]} onChange={(event) => update(`title${suffix}`, event.target.value)} />
                </label>
                <label className="field">
                  <span>Body</span>
                  <textarea rows={3} value={form[`body${suffix}`] || ""} onChange={(event) => update(`body${suffix}`, event.target.value || null)} />
                </label>
                <label className="field">
                  <span>Advertising label</span>
                  <input value={form[`disclosure${suffix}`]} onChange={(event) => update(`disclosure${suffix}`, event.target.value)} />
                </label>
                <label className="field">
                  <span>Affiliate disclosure</span>
                  <textarea
                    rows={4}
                    value={form[`affiliateDisclosure${suffix}`] || ""}
                    onChange={(event) => update(`affiliateDisclosure${suffix}`, event.target.value || null)}
                  />
                  <small>Always shown in full on the advertisement card.</small>
                </label>
              </fieldset>
            ))}
          </div>
        </section>

        <section className="drawer-section">
          <h3>Ranking and frequency</h3>
          <div className="form-grid advertising-ranking-grid">
            <NumberField label="Base priority" hint="Manual score · 0–10" value={form.basePriority} max={10} step={0.1} onChange={(value) => update("basePriority", value)} />
            <NumberField label="Signed-in relevance" hint="0–10" value={form.authenticatedRelevance} max={10} step={0.1} onChange={(value) => update("authenticatedRelevance", value)} />
            <NumberField label="Anonymous relevance" hint="0–10" value={form.anonymousRelevance} max={10} step={0.1} onChange={(value) => update("anonymousRelevance", value)} />
            <NumberField label="Daily delivery cap" hint="Per user · 0 disables delivery" value={form.dailySelectionCap} max={100} onChange={(value) => update("dailySelectionCap", value)} />
            <NumberField label="Minimum repeat gap" hint="Hours per user" value={form.minimumSecondsBetweenSelections / 3600} max={720} onChange={(value) => update("minimumSecondsBetweenSelections", Math.round(value * 3600))} />
            <NumberField label="Cooldown after open" hint="Days per user" value={form.postViewCooldownSeconds / 86400} max={365} onChange={(value) => update("postViewCooldownSeconds", Math.round(value * 86400))} />
            <NumberField label="Minimum public items" hint="Feed size required before insertion" value={form.minimumFeedItemCount} min={1} max={100} onChange={(value) => update("minimumFeedItemCount", value)} />
            <div className="advertising-position-fields">
              <NumberField label="Earliest position" hint="0-based feed index" value={form.earliestPosition} max={99} onChange={(value) => update("earliestPosition", value)} />
              <NumberField label="Latest position" hint="0-based feed index" value={form.latestPosition} max={99} onChange={(value) => update("latestPosition", value)} />
            </div>
          </div>
        </section>

        <section className="drawer-section">
          <h3>Schedule</h3>
          <div className="form-grid">
            <label className="field">
              <span>Starts at</span>
              <input type="datetime-local" value={localDateTime(form.startsAt)} onChange={(event) => update("startsAt", instant(event.target.value))} />
            </label>
            <label className="field">
              <span>Ends at</span>
              <input type="datetime-local" value={localDateTime(form.endsAt)} onChange={(event) => update("endsAt", instant(event.target.value))} />
            </label>
          </div>
        </section>

        {attempted && errors.length ? (
          <InlineNotice tone="danger">
            <span><strong>Review the campaign before saving.</strong><br />{errors.join(" ")}</span>
          </InlineNotice>
        ) : null}
        {mutation.error ? <InlineNotice tone="danger">{mutation.error.message}</InlineNotice> : null}
        <div className="drawer-form-actions advertising-editor-actions">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" busy={mutation.isPending}>{campaign ? "Save campaign" : "Create campaign"}</Button>
        </div>
      </form>
      {campaign ? <CampaignAudienceActivity campaign={campaign} /> : null}
    </DetailDrawer>
  );
}

function RankingPolicy({ policy }) {
  if (!policy) return null;
  return (
    <section className="workspace-section advertising-policy">
      <div className="section-heading">
        <div>
          <h2>How the server ranks advertisements</h2>
          <p>The app preserves this server order and only renders the unified public-question feed.</p>
        </div>
        <StatusBadge tone="info">{policy.exploitationPercent}% best score · {policy.explorationPercent}% explore</StatusBadge>
      </div>
      <div className="advertising-policy-body">
        <p>First, campaigns that do not match the schedule, audience, frequency cap, cooldown, destination, or feed size are removed.</p>
        <code>
          score = priority×{policy.basePriorityWeight} + relevance×{policy.relevanceWeight} + smoothed open rate×{policy.smoothedViewRateWeight} + exploration×{policy.explorationWeight} + freshness×{policy.freshnessWeight} − today deliveries×{policy.dailySelectionPenalty} − smoothed not-interested rate×{policy.notInterestedPenaltyWeight}
        </code>
        <p>The highest score normally wins. Ads that people mark as not interested lose score for everyone, while that exact campaign is permanently removed for the person who hid it. {policy.explorationPercent}% of requests test ranks 2–{policy.selectionPoolSize} so new or less-exposed campaigns can collect evidence.</p>
      </div>
    </section>
  );
}

function AdvertisingWorkspace() {
  const queryClient = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [audience, setAudience] = useState("");
  const [editor, setEditor] = useState(undefined);

  const campaignsQuery = useQuery({
    queryKey: ["admin", "native-ad-campaigns", query, status, audience, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (query) params.set("query", query);
      if (status) params.set("status", status);
      if (audience) params.set("audience", audience);
      return adminFetch(`/native-ad-campaigns?${params}`);
    },
    placeholderData: keepPreviousData,
  });

  const campaigns = Array.isArray(campaignsQuery.data?.campaigns) ? campaignsQuery.data.campaigns : [];
  const total = Number(campaignsQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const deliveries = campaigns.reduce((sum, campaign) => sum + Number(campaign.performanceSelections || 0), 0);
  const impressions = campaigns.reduce((sum, campaign) => sum + Number(campaign.performanceImpressions || 0), 0);
  const opens = campaigns.reduce((sum, campaign) => sum + Number(campaign.performanceViews || 0), 0);
  const suppressions = campaigns.reduce((sum, campaign) => sum + Number(campaign.performanceSuppressions || 0), 0);

  const columns = useMemo(() => [
    {
      key: "campaign",
      label: "Campaign",
      render: (row) => (
        <div className="advertising-campaign-summary">
          {row.imageUrl ? <img src={row.imageUrl} alt="" /> : <div className="advertising-image-placeholder">AD</div>}
          <div className="primary-cell advertising-campaign-cell">
            <strong>{row.titleKo || row.titleEn || row.campaignKey}</strong>
            <span>{row.campaignKey}</span>
          </div>
        </div>
      ),
    },
    { key: "status", label: "Status", render: (row) => {
      const value = campaignStatus(row);
      return <StatusBadge tone={statusTone(value)}>{value}</StatusBadge>;
    } },
    { key: "audience", label: "Audience" },
    { key: "deliveries", label: "30d deliveries", render: (row) => Number(row.performanceSelections || 0).toLocaleString() },
    { key: "impressions", label: "30d seen", render: (row) => Number(row.performanceImpressions || 0).toLocaleString() },
    { key: "opens", label: "30d opens", render: (row) => Number(row.performanceViews || 0).toLocaleString() },
    { key: "rate", label: "Open after seen", render: (row) => percentage(row.performanceViewableOpenRate) },
    { key: "not-interested", label: "Not interested", render: (row) => `${Number(row.performanceSuppressions || 0).toLocaleString()} · ${percentage(row.performanceSuppressionRate)}` },
    { key: "schedule", label: "Schedule", render: campaignSchedule },
  ], []);

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["admin", "native-ad-campaigns"] });
  }

  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Advertising campaigns"
        description="Add Coupang URLs, control targeting and frequency, and inspect the ranking signals mixed into public questions."
        actions={(
          <>
            <Button variant="secondary" icon={RefreshCw} onClick={refresh} busy={campaignsQuery.isFetching}>Refresh</Button>
            <Button icon={Plus} onClick={() => setEditor(null)}>New campaign</Button>
          </>
        )}
      />

      <div className="metric-strip advertising-metric-strip">
        <div><span>Campaigns</span><strong>{total.toLocaleString()}</strong></div>
        <div><span>Active on page</span><strong>{campaigns.filter((item) => campaignStatus(item) === "ACTIVE").length}</strong></div>
        <div><span>30d deliveries on page</span><strong>{deliveries.toLocaleString()}</strong></div>
        <div><span>30d seen on page</span><strong>{impressions.toLocaleString()}</strong></div>
        <div><span>30d open after seen</span><strong>{percentage(impressions ? Math.min(opens, impressions) / impressions : 0)}</strong></div>
        <div><span>30d not interested on page</span><strong>{suppressions.toLocaleString()}</strong></div>
      </div>

      <section className="workspace-section advertising-list">
        <div className="section-heading toolbar-heading advertising-toolbar">
          <SearchField
            value={queryDraft}
            onChange={setQueryDraft}
            onSubmit={() => {
              setOffset(0);
              setQuery(queryDraft.trim());
            }}
            label="Advertising campaign search"
            placeholder="Campaign key or title"
          />
          <div className="advertising-filter-fields">
            <label className="field compact-field">
              <span>Status</span>
              <select value={status} onChange={(event) => { setOffset(0); setStatus(event.target.value); }}>
                <option value="">All</option>
                <option value="ACTIVE">Active</option>
                <option value="PAUSED">Paused</option>
                <option value="SCHEDULED">Scheduled</option>
                <option value="ENDED">Ended</option>
              </select>
            </label>
            <label className="field compact-field">
              <span>Audience</span>
              <select value={audience} onChange={(event) => { setOffset(0); setAudience(event.target.value); }}>
                <option value="">All</option>
                <option value="AUTHENTICATED">Signed-in</option>
                <option value="ANONYMOUS">Anonymous</option>
              </select>
            </label>
          </div>
        </div>
        {campaignsQuery.error ? <InlineNotice tone="danger">{campaignsQuery.error.message}</InlineNotice> : null}
        <DataTable
          columns={columns}
          rows={campaigns}
          rowKey={(row) => row.id}
          onRowClick={(row) => setEditor(row)}
          emptyText="No advertising campaigns match these filters."
          loading={campaignsQuery.isLoading || campaignsQuery.isPlaceholderData}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + campaigns.length, total)} of ${total}` : "0 campaigns"}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
          fetching={campaignsQuery.isFetching}
        />
      </section>

      <RankingPolicy policy={campaignsQuery.data?.rankingPolicy} />

      {editor !== undefined ? (
        <CampaignEditor
          campaign={editor}
          onClose={() => setEditor(undefined)}
          onSaved={() => {
            setEditor(undefined);
            setOffset(0);
            refresh();
          }}
        />
      ) : null}
    </>
  );
}

export function AdvertisingPage() {
  return <AdvertisingWorkspace />;
}
