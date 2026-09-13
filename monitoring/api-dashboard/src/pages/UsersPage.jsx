import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Plus, RefreshCw, Save } from "lucide-react";
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
import { ObjectInspector } from "../components/ObjectInspector.jsx";
import { AdminNotificationComposer } from "../components/AdminNotificationComposer.jsx";
import { formatDateTime, membershipPlanName, statusTone } from "../lib/format.js";

const PAGE_SIZE = 20;
const LIMIT_PRESETS = [10, 50, 100];
const VOICE_LIMIT_PRESETS = [3_600, 18_000, 36_000];

function formatQuestionCount(value) {
  return Number(value || 0).toLocaleString();
}

function formatVoiceTime(value) {
  const seconds = Math.max(0, Number(value) || 0);
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return remainder ? `${minutes.toLocaleString()}m ${remainder}s` : `${minutes.toLocaleString()}m`;
}

function quotaUsagePercent(user) {
  const limit = Number(user.monthlyLimit) || 0;
  const used = (Number(user.usedCount) || 0) + (Number(user.reservedCount) || 0);
  if (limit <= 0) return used > 0 ? 100 : 0;
  return Math.min(100, Math.round((used / limit) * 100));
}

function voiceQuotaUsagePercent(user) {
  const limit = Number(user.monthlyVoiceSecondsLimit) || 0;
  const used = (Number(user.voiceUsedSeconds) || 0) + (Number(user.voiceReservedSeconds) || 0);
  if (limit <= 0) return used > 0 ? 100 : 0;
  return Math.min(100, Math.round((used / limit) * 100));
}

function VoiceQuotaEditor({ user, onSaved }) {
  const hasOverride = user.monthlyVoiceSecondsLimitOverride !== null
    && user.monthlyVoiceSecondsLimitOverride !== undefined;
  const [overrideSeconds, setOverrideSeconds] = useState(
    hasOverride ? String(user.monthlyVoiceSecondsLimitOverride) : "",
  );
  const parsedOverride = overrideSeconds.trim() === "" ? null : Number(overrideSeconds);
  const isValidOverride = parsedOverride === null
    || (Number.isInteger(parsedOverride) && parsedOverride >= 0 && parsedOverride <= 31_536_000);
  const mutation = useMutation({
    mutationFn: () => adminFetch(`/users/${user.id}/voice-limit`, {
      method: "PATCH",
      body: JSON.stringify({ monthlyVoiceSecondsLimitOverride: parsedOverride }),
    }),
    onSuccess: onSaved,
  });

  return (
    <section className="drawer-section quota-manager">
      <div className="quota-manager-heading">
        <div>
          <span className="quota-eyebrow">Voice Tutor allowance</span>
          <h3>Voice time in this quota period</h3>
        </div>
        <StatusBadge tone={user.voiceRemainingSeconds > 0 ? "success" : "warning"}>{membershipPlanName(user.tierCode)}</StatusBadge>
      </div>

      <div className="quota-stat-grid">
        <div>
          <span>Current limit</span>
          <strong>{formatVoiceTime(user.monthlyVoiceSecondsLimit)}</strong>
          <small>{hasOverride ? "user override" : `${formatVoiceTime(user.tierMonthlyVoiceSecondsLimit)} plan default`}</small>
        </div>
        <div>
          <span>Used</span>
          <strong>{formatVoiceTime(user.voiceUsedSeconds)}</strong>
          <small>{formatVoiceTime(user.voiceReservedSeconds)} reserved</small>
        </div>
        <div>
          <span>Remaining</span>
          <strong>{formatVoiceTime(user.voiceRemainingSeconds)}</strong>
          <small>available now</small>
        </div>
      </div>
      <div
        className="quota-progress"
        role="progressbar"
        aria-label="Voice Tutor quota used"
        aria-valuemin="0"
        aria-valuemax={Number(user.monthlyVoiceSecondsLimit) || 0}
        aria-valuenow={Math.min(
          (Number(user.voiceUsedSeconds) || 0) + (Number(user.voiceReservedSeconds) || 0),
          Number(user.monthlyVoiceSecondsLimit) || 0,
        )}
      >
        <span style={{ width: `${voiceQuotaUsagePercent(user)}%` }} />
      </div>

      <p className="section-description">{formatDateTime(user.voicePeriodStartedAt)} → {formatDateTime(user.voiceResetAt)}</p>

      <div className="quota-target-editor">
        <div>
          <h4>Persistent user cap</h4>
          <p>Set a monthly cap in seconds. Leave it blank to follow the user's plan default after every reset.</p>
        </div>
        <label className="field quota-limit-field">
          <span>Voice limit override</span>
          <div className="quota-number-input">
            <input
              type="number"
              min="0"
              max="31536000"
              step="1"
              value={overrideSeconds}
              placeholder="Plan default"
              onChange={(event) => setOverrideSeconds(event.target.value)}
            />
            <span>seconds</span>
          </div>
        </label>
        <div className="quota-presets" aria-label="Quick Voice Tutor limit overrides">
          {VOICE_LIMIT_PRESETS.map((seconds) => (
            <button type="button" key={seconds} onClick={() => setOverrideSeconds(String(seconds))}>
              {formatVoiceTime(seconds)}
            </button>
          ))}
          <button type="button" onClick={() => setOverrideSeconds("")}>Use plan</button>
        </div>
      </div>

      {!isValidOverride ? (
        <InlineNotice tone="warning" compact>Enter 0–31,536,000 whole seconds, or leave blank to use the plan default.</InlineNotice>
      ) : null}
      <div className="drawer-form-actions">
        {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
        <Button
          icon={Save}
          busy={mutation.isPending}
          disabled={!isValidOverride}
          onClick={() => mutation.mutate()}
        >
          Save Voice Tutor limit
        </Button>
      </div>
    </section>
  );
}

function CurrentPeriodQuotaEditor({ user, onSaved }) {
  const currentLimit = Number(user.monthlyLimit) || 0;
  const usedCount = Number(user.usedCount) || 0;
  const reservedCount = Number(user.reservedCount) || 0;
  const [bonusDelta, setBonusDelta] = useState("50");
  const [reason, setReason] = useState("");
  const parsedDelta = Number(bonusDelta);
  const isValidDelta = Number.isInteger(parsedDelta) && parsedDelta !== 0 && Math.abs(parsedDelta) <= 10_000;
  const usagePercent = quotaUsagePercent(user);
  const mutation = useMutation({
    mutationFn: () => adminFetch(`/users/${user.id}/quota-adjustments`, {
      method: "POST",
      body: JSON.stringify({
        bonusDelta: parsedDelta,
        reason: reason.trim(),
        idempotencyKey: `admin-${user.id}-${Date.now()}`,
      }),
    }),
    onSuccess: onSaved,
  });
  return (
    <section className="drawer-section quota-manager">
      <div className="quota-manager-heading">
        <div>
          <span className="quota-eyebrow">Current allowance</span>
          <h3>Questions in this quota period</h3>
        </div>
        <StatusBadge tone={user.remainingCount > 0 ? "success" : "warning"}>{membershipPlanName(user.tierCode)}</StatusBadge>
      </div>

      <div className="quota-stat-grid">
        <div><span>Current limit</span><strong>{formatQuestionCount(currentLimit)}</strong><small>{formatQuestionCount(user.baseLimit)} base + {formatQuestionCount(user.bonusLimit)} bonus</small></div>
        <div><span>Used</span><strong>{formatQuestionCount(usedCount)}</strong><small>{formatQuestionCount(reservedCount)} reserved</small></div>
        <div><span>Remaining</span><strong>{formatQuestionCount(user.remainingCount)}</strong><small>available now</small></div>
      </div>
      <div
        className="quota-progress"
        role="progressbar"
        aria-label="Question quota used"
        aria-valuemin="0"
        aria-valuemax={currentLimit}
        aria-valuenow={Math.min(usedCount + reservedCount, currentLimit)}
      >
        <span style={{ width: `${usagePercent}%` }} />
      </div>

      <p className="section-description">{formatDateTime(user.periodStartedAt)} → {formatDateTime(user.resetAt)}</p>

      <div className="quota-target-editor">
        <div>
          <h4>Current-period bonus</h4>
          <p>Add or revoke questions without changing the user's subscription tier.</p>
        </div>
        <label className="field quota-limit-field">
          <span>Bonus change</span>
          <div className="quota-number-input">
            <input
              type="number"
              min="-10000"
              max="10000"
              value={bonusDelta}
              onChange={(event) => setBonusDelta(event.target.value)}
            />
            <span>questions</span>
          </div>
        </label>
        <div className="quota-presets" aria-label="Quick question limit extensions">
          {LIMIT_PRESETS.map((increment) => (
            <button
              type="button"
              key={increment}
              onClick={() => setBonusDelta(String(increment))}
            >
              <Plus size={13} aria-hidden="true" />{increment}
            </button>
          ))}
        </div>
      </div>

      <label className="field">
        <span>Reason</span>
        <input value={reason} maxLength={1000} onChange={(event) => setReason(event.target.value)} placeholder="Why is this adjustment needed?" />
      </label>
      {!isValidDelta ? <InlineNotice tone="warning" compact>Enter a non-zero whole number up to 10,000.</InlineNotice> : null}

      <div className="drawer-form-actions">
        {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
        <Button
          icon={Save}
          busy={mutation.isPending}
          disabled={!isValidDelta || reason.trim().length < 3}
          onClick={() => mutation.mutate()}
        >
          Apply bonus until reset
        </Button>
      </div>
    </section>
  );
}

function BillingLifecycle({ userId }) {
  const queryClient = useQueryClient();
  const timeline = useQuery({
    queryKey: ["admin", "users", userId, "billing-timeline"],
    queryFn: () => adminFetch(`/users/${userId}/billing/timeline?limit=100`),
    enabled: Boolean(userId),
  });
  const reconcile = useMutation({
    mutationFn: () => adminFetch(`/users/${userId}/billing/reconcile`, {
      method: "POST",
      body: JSON.stringify({ reason: "Manual admin reconciliation" }),
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["admin", "users", userId, "billing-timeline"] }),
  });
  const entries = Array.isArray(timeline.data?.entries) ? timeline.data.entries : [];
  const columns = [
    { key: "occurredAt", label: "Time", render: (entry) => formatDateTime(entry.occurredAt) },
    { key: "category", label: "Area", render: (entry) => <StatusBadge>{entry.category}</StatusBadge> },
    { key: "eventType", label: "Event" },
    { key: "status", label: "Status", render: (entry) => entry.status || "-" },
    { key: "reason", label: "Result", render: (entry) => entry.reason || "-" },
  ];
  return (
    <section className="drawer-section order-ledger-section">
      <div className="billing-timeline-heading">
        <div><h3>Billing & quota timeline</h3><p>Subscription, payment, invoice, and quota events in one audit trail.</p></div>
        <Button variant="secondary" icon={RefreshCw} busy={reconcile.isPending} onClick={() => reconcile.mutate()}>Reconcile</Button>
      </div>
      {timeline.error || reconcile.error ? <InlineNotice tone="danger" compact>{timeline.error?.message || reconcile.error?.message}</InlineNotice> : null}
      {timeline.data?.entitlement ? (
        <div className="billing-entitlement-line">
          <StatusBadge tone={statusTone(timeline.data.entitlement.accessStatus)}>{membershipPlanName(timeline.data.entitlement.tierCode)}</StatusBadge>
          <span>{timeline.data.entitlement.accessStatus}</span>
          <span>{timeline.data.entitlement.renewalStatus}</span>
          <span>Synced {formatDateTime(timeline.data.entitlement.synchronizedAt)}</span>
        </div>
      ) : null}
      <DataTable columns={columns} rows={entries} rowKey={(entry) => `${entry.category}-${entry.eventId}`} emptyText="No billing or quota events." loading={timeline.isLoading} />
    </section>
  );
}

function TierRow({ tier, onSaved }) {
  const [limit, setLimit] = useState(tier.monthlyQuestionLimit);
  const freePlan = tier.tierCode === "TIER1";
  const [voiceLimit, setVoiceLimit] = useState(tier.monthlyVoiceSecondsLimit || 0);
  const mutation = useMutation({
    mutationFn: () => adminFetch(`/membership-tiers/${encodeURIComponent(tier.tierCode)}`, {
      method: "PATCH",
      body: JSON.stringify({
        monthlyQuestionLimit: Number(limit),
        monthlyVoiceSecondsLimit: freePlan ? 0 : Number(voiceLimit),
      }),
    }),
    onSuccess: onSaved,
  });
  return (
    <tr>
      <td><strong>{membershipPlanName(tier.tierCode)}</strong><small>{tier.description || "Internal plan"}</small></td>
      <td>
        <label className="compact-input">
          <input type="number" min="0" max="1000000" value={limit} onChange={(event) => setLimit(event.target.value)} />
          <span>questions / month</span>
        </label>
      </td>
      <td>
        <label className="compact-input">
          <input type="number" min="0" max="31536000" value={freePlan ? 0 : voiceLimit} disabled={freePlan} onChange={(event) => setVoiceLimit(event.target.value)} />
          <span>{freePlan ? "Voice unavailable on Free" : "voice seconds / month"}</span>
        </label>
      </td>
      <td className="action-cell">
        <Button variant="secondary" icon={Save} busy={mutation.isPending} onClick={() => mutation.mutate()}>Save</Button>
        {mutation.error ? <span className="cell-error">{mutation.error.message}</span> : null}
      </td>
    </tr>
  );
}

function UsersWorkspace() {
  const queryClient = useQueryClient();
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const tiersQuery = useQuery({
    queryKey: ["admin", "tiers"],
    queryFn: () => adminFetch("/membership-tiers"),
  });
  const usersQuery = useQuery({
    queryKey: ["admin", "users", query, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (query) params.set("query", query);
      return adminFetch(`/users?${params}`);
    },
    placeholderData: keepPreviousData,
  });
  const users = Array.isArray(usersQuery.data?.users) ? usersQuery.data.users : [];
  const tiers = Array.isArray(tiersQuery.data) ? tiersQuery.data : [];
  const total = Number(usersQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "user",
      label: "User",
      render: (user) => (
        <div className="primary-cell">
          <strong>{user.displayName || "(no name)"}</strong>
          <span>{user.email || `User ${user.id}`}</span>
        </div>
      ),
    },
    { key: "id", label: "ID", className: "mono" },
    { key: "provider", label: "Provider" },
    { key: "status", label: "Status", render: (user) => <StatusBadge tone={statusTone(user.status)}>{user.status}</StatusBadge> },
    {
      key: "usage",
      label: "Questions",
      render: (user) => (
        <span className="usage-cell quota-table-usage">
          <span><strong>{formatQuestionCount(user.usedCount)}</strong> of {formatQuestionCount(user.monthlyLimit)}</span>
          <span className="quota-table-progress"><i style={{ width: `${quotaUsagePercent(user)}%` }} /></span>
          <small>{formatQuestionCount(user.remainingCount)} remaining</small>
        </span>
      ),
    },
    {
      key: "voiceUsage",
      label: "Voice",
      render: (user) => (
        <span className="usage-cell quota-table-usage">
          <span><strong>{formatVoiceTime(user.voiceUsedSeconds)}</strong> of {formatVoiceTime(user.monthlyVoiceSecondsLimit)}</span>
          <span className="quota-table-progress"><i style={{ width: `${voiceQuotaUsagePercent(user)}%` }} /></span>
          <small>{formatVoiceTime(user.voiceRemainingSeconds)} remaining</small>
        </span>
      ),
    },
    { key: "tierCode", label: "Plan", render: (user) => <StatusBadge>{membershipPlanName(user.tierCode)}</StatusBadge> },
    { key: "resetAt", label: "Reset", render: (user) => formatDateTime(user.resetAt) },
  ], []);

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["admin"] });
  }

  return (
    <>
      {(tiersQuery.error || usersQuery.error) ? (
        <InlineNotice tone="danger">{tiersQuery.error?.message || usersQuery.error?.message}</InlineNotice>
      ) : null}

      <section className="workspace-section">
        <div className="section-heading">
          <div><h2>Plan limits</h2><p>Base monthly capacity for each subscription tier.</p></div>
        </div>
        <div className="table-frame compact-table">
          <table className="data-table">
            <thead><tr><th>Plan</th><th>Question capacity</th><th>Voice capacity</th><th>Action</th></tr></thead>
            <tbody>{tiers.map((tier) => <TierRow key={tier.tierCode} tier={tier} onSaved={refresh} />)}</tbody>
          </table>
          {!tiersQuery.isLoading && tiers.length === 0 ? <div className="table-state">No membership tiers configured.</div> : null}
        </div>
      </section>

      <section className="workspace-section">
        <div className="section-heading toolbar-heading">
          <div><h2>Users</h2><p>{total.toLocaleString()} matching accounts</p></div>
          <SearchField
            value={draftQuery}
            onChange={setDraftQuery}
            onSubmit={() => { setOffset(0); setQuery(draftQuery.trim()); }}
            label="User search"
            placeholder="Email, display name, or user ID"
          />
        </div>
        <DataTable
          columns={columns}
          rows={users}
          rowKey={(user) => user.id}
          onRowClick={setSelected}
          emptyText={query ? "No users match this search." : "No users found."}
          loading={usersQuery.isLoading}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={`${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}`}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>

      <DetailDrawer
        open={Boolean(selected)}
        title={selected?.displayName || `User ${selected?.id}`}
        subtitle={selected?.email}
        onClose={() => setSelected(null)}
      >
        {selected ? (
          <>
            <CurrentPeriodQuotaEditor
              key={`${selected.id}-${selected.bonusLimit}-${selected.reservedCount}-${selected.resetAt}`}
              user={selected}
              onSaved={() => {
                refresh();
              }}
            />
            <VoiceQuotaEditor
              key={`${selected.id}-${selected.monthlyVoiceSecondsLimitOverride}-${selected.voiceUsedSeconds}-${selected.voiceReservedSeconds}-${selected.voiceResetAt}`}
              user={selected}
              onSaved={(updated) => {
                setSelected(updated);
                refresh();
              }}
            />
            <div className="detail-summary user-account-summary">
              <div><span>Status</span><StatusBadge tone={statusTone(selected.status)}>{selected.status}</StatusBadge></div>
              <div><span>Provider</span><strong>{selected.provider}</strong></div>
              <div><span>Plan</span><strong>{membershipPlanName(selected.tierCode)}</strong></div>
              <div><span>User ID</span><strong>{selected.id}</strong></div>
            </div>
            <BillingLifecycle userId={selected.id} />
            <AdminNotificationComposer
              endpoint={`/users/${selected.id}/notifications`}
              title="Send push to this user"
              description="Send an independent in-app notification and APNs push to the selected user."
              initialTitle="BuddyStudy에서 알려드려요"
              initialBody=""
            />
            <ObjectInspector value={selected} title="User object" />
          </>
        ) : null}
      </DetailDrawer>
    </>
  );
}

export function UsersPage() {
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Users & quotas"
        description="Search accounts, manage question and Voice Tutor capacity, and send a direct push to a selected user."
      />
      <UsersWorkspace />
    </>
  );
}
