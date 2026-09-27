import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Eye, Plus, RefreshCw, Save, Send, X } from "lucide-react";
import { useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import { DataTable, DetailDrawer, PageHeader, Pagination, SearchField, StatusBadge } from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { formatDateTime } from "../lib/format.js";
import { campaignAudienceLabel, campaignRequest, formatCampaignCTR, MAX_SELECTED_USERS, PUSH_DESTINATIONS, validateCampaignDraft } from "../lib/pushCampaignModel.js";

const PAGE_SIZE = 20;
const INITIAL_DRAFT = { title: "", body: "", destination: "public", questionId: "", audience: "ALL_REGISTERED", userIds: [] };
const count = (value) => Number(value || 0).toLocaleString();

function AudiencePicker({ selected, onChange, disabled }) {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const usersQuery = useQuery({
    queryKey: ["admin", "push-audience-users", query, offset],
    queryFn: ({ signal }) => adminFetch(`/users?${new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset), query })}`, { signal }),
    placeholderData: keepPreviousData,
  });
  const users = usersQuery.data?.users || [];
  const total = Number(usersQuery.data?.totalCount) || 0;
  function toggle(user) {
    onChange(selected.some((item) => item.id === user.id)
      ? selected.filter((item) => item.id !== user.id)
      : [...selected, user]);
  }
  return (
    <section className="push-audience-picker" aria-label="Choose campaign recipients">
      <SearchField value={search} onChange={setSearch} label="Push audience" placeholder="Email, name, or user ID" onSubmit={() => { setOffset(0); setQuery(search.trim()); }} />
      <p className="section-description">{selected.length} of {MAX_SELECTED_USERS} users selected. Search results do not change your selection.</p>
      {selected.length ? <div className="push-selected-users">{selected.map((user) => (
        <button type="button" key={user.id} disabled={disabled} onClick={() => toggle(user)} aria-label={`Remove ${user.displayName || `user ${user.id}`}`}>
          {user.displayName || `User ${user.id}`} <span>#{user.id}</span><X size={12} aria-hidden="true" />
        </button>
      ))}</div> : null}
      {usersQuery.error ? <InlineNotice tone="danger" compact>{usersQuery.error.message}</InlineNotice> : null}
      <div className="push-user-results" aria-busy={usersQuery.isFetching}>
        {users.map((user) => {
          const checked = selected.some((item) => item.id === user.id);
          return <label key={user.id} className="push-user-row">
            <input type="checkbox" checked={checked} disabled={disabled || usersQuery.isPlaceholderData || (!checked && selected.length >= MAX_SELECTED_USERS)} onChange={() => toggle(user)} />
            <span><strong>{user.displayName || `User ${user.id}`}</strong><small>{user.email || "No email"} · #{user.id}</small></span>
          </label>;
        })}
        {!usersQuery.isLoading && !users.length ? <p className="section-description">No users match this search.</p> : null}
        {usersQuery.isLoading ? <p className="section-description">Loading users…</p> : null}
      </div>
      <Pagination page={Math.floor(offset / PAGE_SIZE) + 1} totalPages={Math.max(1, Math.ceil(total / PAGE_SIZE))} fetching={usersQuery.isFetching}
        label={`${total ? offset + 1 : 0}–${Math.min(offset + users.length, total)} of ${total}`} onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))} onNext={() => setOffset(offset + PAGE_SIZE)} />
    </section>
  );
}

function PushPreview({ campaign, label = "Message content preview" }) {
  return <section className="push-preview" aria-label={label}>
    <p className="section-description">Message content preview · iOS controls push formatting and truncation.</p><div className="push-preview-notification"><div className="push-preview-brand"><Bell size={15} aria-hidden="true" /> BUDDYSTUDY <span>now</span></div>
      <strong>{campaign.title}</strong><p>{campaign.body}</p>
    </div>
    <dl className="push-preview-summary">
      <div><dt>Tap opens</dt><dd><code>{campaign.deepLink}</code></dd></div>
      <div><dt>Audience</dt><dd>{campaignAudienceLabel(campaign.audience)}</dd></div>
      {campaign.audience === "SELECTED_USERS" && campaign.userIds?.length ? <div><dt>Selected user IDs</dt><dd>{campaign.userIds.join(", ")}</dd></div> : null}
      <div><dt>Eligible recipients</dt><dd>{count(campaign.recipientCount)} users</dd></div>
    </dl>
  </section>;
}

function CampaignComposer({ onClose, onSaved }) {
  const [draft, setDraft] = useState(INITIAL_DRAFT);
  const [selectedUsers, setSelectedUsers] = useState([]);
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const [submitted, setSubmitted] = useState(false);
  const [reviewed, setReviewed] = useState(null);
  const errors = validateCampaignDraft(draft);
  const preview = useMutation({
    mutationFn: (request) => adminFetch("/push-campaigns/preview", { method: "POST", body: JSON.stringify(request) }),
    onSuccess: (result, request) => setReviewed({ campaign: { ...result, userIds: request.userIds }, request }),
  });
  const save = useMutation({
    mutationFn: () => adminFetch("/push-campaigns", { method: "POST", body: JSON.stringify(reviewed.request) }),
    onSuccess: onSaved,
  });
  const busy = preview.isPending || save.isPending;
  function update(key, value) {
    setDraft((current) => ({ ...current, [key]: value }));
    setIdempotencyKey(crypto.randomUUID());
    setReviewed(null);
    preview.reset();
    save.reset();
  }
  function review() {
    setSubmitted(true);
    if (!errors.length) preview.mutate(campaignRequest(draft, idempotencyKey));
  }
  return <DetailDrawer open title="New push campaign" subtitle="Write the notification, choose its destination, and review the audience." onClose={busy ? undefined : onClose}>
    <fieldset disabled={busy} className="push-composer-fields">
      <div className="form-grid">
        <label className="field push-wide-field"><span>Push title</span><input aria-label="Push title" maxLength={160} value={draft.title} onChange={(event) => update("title", event.target.value)} placeholder="This week's conversations worth reading" /></label>
        <label className="field push-wide-field"><span>Message</span><textarea aria-label="Message" rows={4} maxLength={2000} value={draft.body} onChange={(event) => update("body", event.target.value)} placeholder="Tell people why they should open this notification." /><small>{draft.body.length} / 2,000 characters</small></label>
        <label className="field"><span>Landing page</span><select aria-label="Landing page" value={draft.destination} onChange={(event) => update("destination", event.target.value)}>{PUSH_DESTINATIONS.map((destination) => <option key={destination.value} value={destination.value}>{destination.label}</option>)}</select></label>
        {draft.destination === "public-question" ? <label className="field"><span>Public question ID</span><input aria-label="Public question ID" inputMode="numeric" value={draft.questionId} onChange={(event) => update("questionId", event.target.value)} placeholder="123" /><small>The server checks that this question is still public.</small></label> : null}
        <label className="field"><span>Audience</span><select aria-label="Audience" value={draft.audience} onChange={(event) => update("audience", event.target.value)}><option value="ALL_REGISTERED">All registered users</option><option value="SELECTED_USERS">Selected users</option></select></label>
      </div>
      <p className="section-description">Recipients are active registered members who receive an inbox notification. Push respects marketing notification preferences and requires an eligible iOS device, so inbox recipients can exceed available push targets. The server checks the audience again when you queue the draft.</p>
    </fieldset>
    {draft.audience === "SELECTED_USERS" ? <AudiencePicker selected={selectedUsers} disabled={busy} onChange={(users) => { setSelectedUsers(users); update("userIds", users.map((user) => user.id)); }} /> : null}
    {submitted && errors.length ? <InlineNotice tone="danger">{errors.join(" ")}</InlineNotice> : null}
    {preview.error || save.error ? <InlineNotice tone="danger">{preview.error?.message || save.error?.message}</InlineNotice> : null}
    {reviewed ? <><PushPreview campaign={reviewed.campaign} /><InlineNotice tone={reviewed.campaign.recipientCount > 0 ? "info" : "warning"}>{reviewed.campaign.recipientCount > 0 ? "Saving creates a draft. Review it, then choose Queue push to send." : "No active registered recipients currently qualify. Change the audience and preview again."}</InlineNotice></> : null}
    <div className="drawer-form-actions">
      <Button variant="ghost" disabled={busy} onClick={onClose}>Cancel</Button>
      <Button variant="secondary" icon={Eye} busy={preview.isPending} disabled={busy} onClick={review}>Preview audience & landing</Button>
      {reviewed ? <Button icon={Save} busy={save.isPending} disabled={busy || reviewed.campaign.recipientCount < 1} onClick={() => save.mutate()}>Save draft</Button> : null}
    </div>
  </DetailDrawer>;
}

function CampaignMetrics({ campaign }) {
  return <>
    <div className="push-metrics">
      <div><span>Recipients</span><strong>{count(campaign.recipientCount)}</strong></div>
      <div><span>Queued</span><strong>{count(campaign.queuedCount)}</strong></div>
      <div><span>Provider accepted</span><strong>{count(campaign.acceptedCount)}</strong></div>
      <div><span>Errors / skipped</span><strong>{count(campaign.failedCount)}</strong></div>
      <div><span>Unique push opens</span><strong>{count(campaign.uniqueOpenCount)}</strong></div>
      <div><span>Unique inbox opens</span><strong>{count(campaign.inboxOpenCount)}</strong></div>
      <div><span>CTR · accepted</span><strong>{formatCampaignCTR(campaign)}</strong></div>
    </div>
    <p className="section-description">CTR = unique recipients who opened the push ÷ recipients whose push was accepted by APNs. APNs acceptance does not confirm device delivery. In-app inbox opens are excluded. Errors / skipped reflects the latest attempt; retries can later move these recipients into accepted.</p>
  </>;
}

function CampaignDetails({ campaignId, onClose, onUpdated }) {
  const queryClient = useQueryClient();
  const details = useQuery({
    queryKey: ["admin", "push-campaign", campaignId],
    queryFn: ({ signal }) => adminFetch(`/push-campaigns/${campaignId}`, { signal }),
    refetchInterval: (query) => query.state.data?.status === "QUEUED" ? 15000 : false,
  });
  const campaign = details.data;
  const send = useMutation({
    mutationFn: () => adminFetch(`/push-campaigns/${campaignId}/send`, { method: "POST" }),
    onSuccess: (result) => {
      queryClient.setQueryData(["admin", "push-campaign", campaignId], result);
      onUpdated();
    },
  });
  return <DetailDrawer open title={campaign?.title || "Push campaign"} subtitle={campaign ? `${campaign.status} · Created ${formatDateTime(campaign.createdAt)}` : "Loading campaign…"} onClose={send.isPending ? undefined : onClose}>
    {details.error || send.error ? <InlineNotice tone="danger">{details.error?.message || send.error?.message}</InlineNotice> : null}
    {campaign ? <>
      <PushPreview campaign={campaign} />
      {campaign.status === "DRAFT" ? <>
        <InlineNotice tone="warning">This saved draft has not been sent. Queue push sends this notification to the eligible audience shown above; the server rechecks eligibility when sending.</InlineNotice>
        <div className="drawer-form-actions"><Button icon={Send} busy={send.isPending} disabled={send.isPending} onClick={() => send.mutate()}>Queue push</Button></div>
      </> : <InlineNotice tone="success">Campaign queued {formatDateTime(campaign.sentAt)}. Delivery and open counts refresh every 15 seconds while this panel is open.</InlineNotice>}
      <section className="drawer-section"><h3>Delivery & engagement</h3><CampaignMetrics campaign={campaign} /></section>
    </> : null}
  </DetailDrawer>;
}

const COLUMNS = [
  { key: "campaign", label: "Campaign", render: (campaign) => <div className="primary-cell push-campaign-cell"><strong>{campaign.title}</strong><span>{campaign.deepLink}</span></div> },
  { key: "status", label: "Status", render: (campaign) => <StatusBadge tone={campaign.status === "DRAFT" ? "neutral" : "info"}>{campaign.status}</StatusBadge> },
  { key: "recipientCount", label: "Recipients", render: (campaign) => count(campaign.recipientCount) },
  { key: "queuedCount", label: "Queued", render: (campaign) => count(campaign.queuedCount) },
  { key: "acceptedCount", label: "Provider accepted", render: (campaign) => count(campaign.acceptedCount) },
  { key: "failedCount", label: "Errors / skipped", render: (campaign) => count(campaign.failedCount) },
  { key: "uniqueOpenCount", label: "Unique push opens", render: (campaign) => count(campaign.uniqueOpenCount) },
  { key: "ctr", label: "CTR · accepted", render: formatCampaignCTR },
  { key: "createdAt", label: "Created", render: (campaign) => formatDateTime(campaign.createdAt) },
];

export function PushCampaignsPage() {
  const queryClient = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [composing, setComposing] = useState(false);
  const [selectedId, setSelectedId] = useState(null);
  const campaignsQuery = useQuery({
    queryKey: ["admin", "push-campaigns", offset],
    queryFn: ({ signal }) => adminFetch(`/push-campaigns?${new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) })}`, { signal }),
    placeholderData: keepPreviousData,
  });
  const campaigns = campaignsQuery.data?.campaigns || [];
  const total = Number(campaignsQuery.data?.totalCount) || 0;
  function refresh() { queryClient.invalidateQueries({ queryKey: ["admin", "push-campaigns"] }); }
  return <>
    <PageHeader eyebrow="Manage" title="Push Admin" description="Send an occasional highlight, choose where it opens, and measure push engagement." actions={<><Button variant="secondary" icon={RefreshCw} busy={campaignsQuery.isFetching} onClick={refresh}>Refresh</Button><Button icon={Plus} onClick={() => setComposing(true)}>New campaign</Button></>} />
    <section className="workspace-section">
      <div className="section-heading"><div><h2>Push campaigns</h2><p>{count(total)} campaigns · Select one to review its message, landing page, and results.</p></div></div>
      {campaignsQuery.error ? <InlineNotice tone="danger">{campaignsQuery.error.message}</InlineNotice> : null}
      <DataTable columns={COLUMNS} rows={campaigns} rowKey={(campaign) => campaign.id} onRowClick={(campaign) => setSelectedId(campaign.id)} loading={campaignsQuery.isLoading || campaignsQuery.isPlaceholderData} emptyText="No push campaigns yet. Create a campaign to preview its message and audience." />
      <Pagination page={Math.floor(offset / PAGE_SIZE) + 1} totalPages={Math.max(1, Math.ceil(total / PAGE_SIZE))} fetching={campaignsQuery.isFetching} label={`${total ? offset + 1 : 0}–${Math.min(offset + campaigns.length, total)} of ${total}`} onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))} onNext={() => setOffset(offset + PAGE_SIZE)} />
    </section>
    <p className="section-description">Provider accepted means APNs accepted the push, not that a device displayed it. CTR uses unique push opens divided by accepted recipients; no accepted recipients is shown as —. Errors / skipped can recover on retry.</p>
    {composing ? <CampaignComposer onClose={() => setComposing(false)} onSaved={(campaign) => { setComposing(false); setOffset(0); queryClient.setQueryData(["admin", "push-campaign", campaign.id], campaign); setSelectedId(campaign.id); refresh(); }} /> : null}
    {selectedId ? <CampaignDetails campaignId={selectedId} onClose={() => setSelectedId(null)} onUpdated={refresh} /> : null}
  </>;
}
