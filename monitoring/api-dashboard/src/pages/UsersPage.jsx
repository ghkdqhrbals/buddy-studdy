import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { CalendarRange, Plus, RotateCcw, Save } from "lucide-react";
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
  const used = Number(user.usedCount) || 0;
  if (limit <= 0) return used > 0 ? 100 : 0;
  return Math.min(100, Math.round((used / limit) * 100));
}

function MembershipEditor({ user, tiers, onSaved }) {
  const [tierCode, setTierCode] = useState(user.tierCode);
  const [override, setOverride] = useState(user.monthlyLimitOverride ?? "");
  const mutation = useMutation({
    mutationFn: () => adminFetch(`/users/${user.id}/membership`, {
      method: "PATCH",
      body: JSON.stringify({
        tierCode,
        monthlyQuestionLimitOverride: override === "" ? null : Number(override),
      }),
    }),
    onSuccess: onSaved,
  });
  return (
    <section className="drawer-section">
      <h3>Default limit after reset</h3>
      <p className="section-description">
        This membership limit applies now when no current-period override exists, and continues after every reset.
      </p>
      <div className="form-grid">
        <label className="field">
          <span>Internal plan</span>
          <select value={tierCode} onChange={(event) => setTierCode(event.target.value)}>
            {tiers.map((tier) => <option key={tier.tierCode}>{tier.tierCode}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Personal recurring limit</span>
          <input
            type="number"
            min="0"
            max="1000000"
            value={override}
            placeholder="Use plan default"
            onChange={(event) => setOverride(event.target.value)}
          />
        </label>
      </div>
      <div className="drawer-form-actions">
        {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
        <Button icon={Save} busy={mutation.isPending} onClick={() => mutation.mutate()}>Save membership</Button>
      </div>
    </section>
  );
}

function CurrentPeriodQuotaEditor({ user, membershipLimit, onSaved }) {
  const currentLimit = Number(user.monthlyLimit) || 0;
  const usedCount = Number(user.usedCount) || 0;
  const [targetLimit, setTargetLimit] = useState(String(currentLimit));
  const parsedTargetLimit = targetLimit === "" ? Number.NaN : Number(targetLimit);
  const isValidTarget = Number.isInteger(parsedTargetLimit)
    && parsedTargetLimit >= 0
    && parsedTargetLimit <= 1_000_000;
  const projectedRemaining = isValidTarget ? Math.max(parsedTargetLimit - usedCount, 0) : null;
  const limitDelta = isValidTarget ? parsedTargetLimit - currentLimit : 0;
  const usagePercent = quotaUsagePercent(user);
  const limitSource = user.currentPeriodQuestionLimitOverride != null
    ? "Current-period override"
    : user.monthlyLimitOverride != null
      ? "Personal recurring limit"
      : `${user.tierCode} plan default`;
  const mutation = useMutation({
    mutationFn: (questionLimitOverride) => adminFetch(`/users/${user.id}/quota/current-period`, {
      method: "PATCH",
      body: JSON.stringify({ questionLimitOverride }),
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
        <StatusBadge tone={user.remainingCount > 0 ? "success" : "warning"}>{limitSource}</StatusBadge>
      </div>

      <div className="quota-stat-grid">
        <div><span>Current limit</span><strong>{formatQuestionCount(currentLimit)}</strong><small>questions total</small></div>
        <div><span>Used</span><strong>{formatQuestionCount(usedCount)}</strong><small>{usagePercent}% consumed</small></div>
        <div><span>Remaining</span><strong>{formatQuestionCount(user.remainingCount)}</strong><small>available now</small></div>
      </div>
      <div
        className="quota-progress"
        role="progressbar"
        aria-label="Question quota used"
        aria-valuemin="0"
        aria-valuemax={currentLimit}
        aria-valuenow={Math.min(usedCount, currentLimit)}
      >
        <span style={{ width: `${usagePercent}%` }} />
      </div>

      <div className="quota-period">
        <CalendarRange size={19} aria-hidden="true" />
        <div>
          <span>Applies to this quota period only</span>
          <strong>{formatDateTime(user.periodStartedAt)} → {formatDateTime(user.resetAt)}</strong>
          <small>The override expires automatically at the reset time.</small>
        </div>
      </div>

      <div className="quota-target-editor">
        <div>
          <h4>Set the total limit until reset</h4>
          <p>Enter the total allowance, or add a common extension to the current limit.</p>
        </div>
        <label className="field quota-limit-field">
          <span>New total limit</span>
          <div className="quota-number-input">
            <input
              type="number"
              min="0"
              max="1000000"
              value={targetLimit}
              onChange={(event) => setTargetLimit(event.target.value)}
            />
            <span>questions</span>
          </div>
        </label>
        <div className="quota-presets" aria-label="Quick question limit extensions">
          {LIMIT_PRESETS.map((increment) => (
            <button
              type="button"
              key={increment}
              onClick={() => setTargetLimit(String(currentLimit + increment))}
            >
              <Plus size={13} aria-hidden="true" />{increment}
            </button>
          ))}
        </div>
      </div>

      {isValidTarget ? (
        <div className="quota-change-preview" data-change={limitDelta === 0 ? "none" : limitDelta > 0 ? "increase" : "decrease"}>
          <div><span>Current</span><strong>{formatQuestionCount(currentLimit)}</strong></div>
          <span className="quota-change-arrow" aria-hidden="true">→</span>
          <div><span>New limit</span><strong>{formatQuestionCount(parsedTargetLimit)}</strong></div>
          <div><span>Available after update</span><strong>{formatQuestionCount(projectedRemaining)}</strong></div>
          <small>{limitDelta === 0 ? "No change" : `${limitDelta > 0 ? "+" : ""}${formatQuestionCount(limitDelta)} questions`}</small>
        </div>
      ) : (
        <InlineNotice tone="warning" compact>Enter a whole number between 0 and 1,000,000.</InlineNotice>
      )}
      {isValidTarget && parsedTargetLimit < usedCount ? (
        <InlineNotice tone="warning" compact>
          This user already used {formatQuestionCount(usedCount)} questions, so the remaining allowance will be 0.
        </InlineNotice>
      ) : null}

      <div className="drawer-form-actions">
        {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
        {user.currentPeriodQuestionLimitOverride != null ? (
          <Button
            variant="ghost"
            icon={RotateCcw}
            busy={mutation.isPending}
            onClick={() => mutation.mutate(null)}
          >
            Restore default ({formatQuestionCount(membershipLimit)})
          </Button>
        ) : null}
        <Button
          icon={Save}
          busy={mutation.isPending}
          disabled={!isValidTarget || parsedTargetLimit === currentLimit}
          onClick={() => mutation.mutate(parsedTargetLimit)}
        >
          Update until reset
        </Button>
      </div>
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
          <div><h2>Plan limits</h2><p>Defaults apply only when a user has no personal override.</p></div>
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
              key={`${selected.id}-${selected.currentPeriodQuestionLimitOverride}-${selected.resetAt}`}
              user={selected}
              membershipLimit={selected.monthlyLimitOverride
                ?? tiers.find((tier) => tier.tierCode === selected.tierCode)?.monthlyQuestionLimit
                ?? selected.monthlyLimit}
              onSaved={(updated) => {
                setSelected(updated);
                refresh();
              }}
            />
            <div className="detail-summary user-account-summary">
              <div><span>Status</span><StatusBadge tone={statusTone(selected.status)}>{selected.status}</StatusBadge></div>
              <div><span>Provider</span><strong>{selected.provider}</strong></div>
              <div><span>Plan</span><strong>{selected.tierCode}</strong></div>
              <div><span>User ID</span><strong>{selected.id}</strong></div>
            </div>
            <MembershipEditor
              key={`${selected.id}-${selected.tierCode}-${selected.monthlyLimitOverride}`}
              user={selected}
              tiers={tiers}
              onSaved={(updated) => {
                setSelected(updated);
                refresh();
              }}
            />
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
