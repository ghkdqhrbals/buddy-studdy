import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Save } from "lucide-react";
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
      <h3>Membership controls</h3>
      <div className="form-grid">
        <label className="field">
          <span>Internal plan</span>
          <select value={tierCode} onChange={(event) => setTierCode(event.target.value)}>
            {tiers.map((tier) => <option key={tier.tierCode}>{tier.tierCode}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Personal monthly limit</span>
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
      render: (user) => <span className="usage-cell"><strong>{user.usedCount}</strong> / {user.monthlyLimit}<small>{user.remainingCount} remaining</small></span>,
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
          loading={usersQuery.isLoading || usersQuery.isFetching}
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
            <div className="detail-summary">
              <div><span>Status</span><StatusBadge tone={statusTone(selected.status)}>{selected.status}</StatusBadge></div>
              <div><span>Provider</span><strong>{selected.provider}</strong></div>
              <div><span>Remaining</span><strong>{selected.remainingCount} / {selected.monthlyLimit}</strong></div>
              <div><span>Reset</span><strong>{formatDateTime(selected.resetAt)}</strong></div>
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
