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
import { formatDateTime, statusTone } from "../lib/format.js";

const PAGE_SIZE = 20;
const LIMIT_PRESETS = [10, 50, 100];

function formatQuestionCount(value) {
  return Number(value || 0).toLocaleString();
}

function quotaUsagePercent(user) {
  const limit = Number(user.monthlyLimit) || 0;
  const used = (Number(user.usedCount) || 0) + (Number(user.reservedCount) || 0);
  if (limit <= 0) return used > 0 ? 100 : 0;
  return Math.min(100, Math.round((used / limit) * 100));
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
        <StatusBadge tone={user.remainingCount > 0 ? "success" : "warning"}>{user.tierCode}</StatusBadge>
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
          <StatusBadge tone={statusTone(timeline.data.entitlement.accessStatus)}>{timeline.data.entitlement.tierCode}</StatusBadge>
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
  const mutation = useMutation({
    mutationFn: () => adminFetch(`/membership-tiers/${encodeURIComponent(tier.tierCode)}`, {
      method: "PATCH",
      body: JSON.stringify({ monthlyQuestionLimit: Number(limit) }),
    }),
    onSuccess: onSaved,
  });
  return (
    <tr>
      <td><strong>{tier.tierCode}</strong><small>{tier.description || "Internal plan"}</small></td>
      <td>
        <label className="compact-input">
          <input type="number" min="0" max="1000000" value={limit} onChange={(event) => setLimit(event.target.value)} />
          <span>questions / month</span>
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
      label: "Usage",
      render: (user) => (
        <span className="usage-cell quota-table-usage">
          <span><strong>{formatQuestionCount(user.usedCount)}</strong> of {formatQuestionCount(user.monthlyLimit)}</span>
          <span className="quota-table-progress"><i style={{ width: `${quotaUsagePercent(user)}%` }} /></span>
          <small>{formatQuestionCount(user.remainingCount)} remaining</small>
        </span>
      ),
    },
    { key: "tierCode", label: "Plan", render: (user) => <StatusBadge>{user.tierCode}</StatusBadge> },
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
            <thead><tr><th>Plan</th><th>Monthly capacity</th><th>Action</th></tr></thead>
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
            <div className="detail-summary user-account-summary">
              <div><span>Status</span><StatusBadge tone={statusTone(selected.status)}>{selected.status}</StatusBadge></div>
              <div><span>Provider</span><strong>{selected.provider}</strong></div>
              <div><span>Plan</span><strong>{selected.tierCode}</strong></div>
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
        description="Search accounts, manage question capacity, and send a direct push to a selected user."
      />
      <UsersWorkspace />
    </>
  );
}
