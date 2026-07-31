import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Save, ShieldCheck } from "lucide-react";
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
import { formatDateTime, statusTone } from "../lib/format.js";

const PAGE_SIZE = 20;
const EMPTY_CREATE_FORM = { username: "", displayName: "", password: "" };

function OperatorForm({ operator, onSaved, onCancel }) {
  const creating = !operator;
  const [form, setForm] = useState(
    creating
      ? EMPTY_CREATE_FORM
      : { displayName: operator.displayName, status: operator.status, password: "" },
  );
  const mutation = useMutation({
    mutationFn: () => adminFetch(
      creating ? "/operators" : `/operators/${operator.id}`,
      {
        method: creating ? "POST" : "PATCH",
        body: JSON.stringify(form),
      },
    ),
    onSuccess: onSaved,
  });

  function field(name, value) {
    setForm((current) => ({ ...current, [name]: value }));
  }

  return (
    <form
      className="operator-form"
      onSubmit={(event) => {
        event.preventDefault();
        mutation.mutate();
      }}
    >
      {creating ? (
        <label className="field">
          <span>Username</span>
          <input
            value={form.username}
            onChange={(event) => field("username", event.target.value)}
            autoComplete="off"
            placeholder="operations.user"
            minLength={3}
            maxLength={64}
            required
          />
        </label>
      ) : null}
      <label className="field">
        <span>Display name</span>
        <input
          value={form.displayName}
          onChange={(event) => field("displayName", event.target.value)}
          minLength={2}
          maxLength={100}
          required
        />
      </label>
      {!creating ? (
        <label className="field">
          <span>Account status</span>
          <select value={form.status} onChange={(event) => field("status", event.target.value)}>
            <option value="ACTIVE">Active</option>
            <option value="DISABLED">Disabled</option>
          </select>
        </label>
      ) : null}
      <label className="field">
        <span>{creating ? "Initial password" : "New password (optional)"}</span>
        <input
          type="password"
          value={form.password}
          onChange={(event) => field("password", event.target.value)}
          autoComplete="new-password"
          minLength={12}
          maxLength={128}
          required={creating}
        />
      </label>
      <p className="operator-password-note">Use at least 12 characters. Passwords are stored only as BCrypt hashes.</p>
      {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
      <div className="drawer-form-actions">
        <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>
        <Button type="submit" icon={creating ? Plus : Save} busy={mutation.isPending}>
          {creating ? "Add administrator" : "Save changes"}
        </Button>
      </div>
    </form>
  );
}

export function AdministratorsPage() {
  const queryClient = useQueryClient();
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const [creating, setCreating] = useState(false);
  const operatorsQuery = useQuery({
    queryKey: ["admin", "operators", query, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (query) params.set("query", query);
      return adminFetch(`/operators?${params}`);
    },
  });
  const operators = Array.isArray(operatorsQuery.data?.operators) ? operatorsQuery.data.operators : [];
  const total = Number(operatorsQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "operator",
      label: "Administrator",
      render: (operator) => (
        <div className="primary-cell">
          <strong>{operator.displayName}</strong>
          <span>@{operator.username}</span>
        </div>
      ),
    },
    {
      key: "status",
      label: "Status",
      render: (operator) => <StatusBadge tone={statusTone(operator.status)}>{operator.status}</StatusBadge>,
    },
    { key: "lastLoginAt", label: "Last sign in", render: (operator) => formatDateTime(operator.lastLoginAt) },
    { key: "createdAt", label: "Created", render: (operator) => formatDateTime(operator.createdAt) },
    { key: "updatedAt", label: "Updated", render: (operator) => formatDateTime(operator.updatedAt) },
  ], []);

  function refresh(updated) {
    queryClient.invalidateQueries({ queryKey: ["admin", "operators"] });
    setCreating(false);
    setSelected(updated || null);
  }

  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Administrators"
        description="Add monitoring operators, rotate credentials, and control console access."
        actions={<Button icon={Plus} onClick={() => setCreating(true)}>Add administrator</Button>}
      />
      <section className="workspace-section">
        <div className="section-heading toolbar-heading">
          <div><h2>Administrator accounts</h2><p>{total.toLocaleString()} accounts</p></div>
          <SearchField
            value={draftQuery}
            onChange={setDraftQuery}
            onSubmit={() => { setOffset(0); setQuery(draftQuery.trim()); }}
            placeholder="Username or display name"
            label="Administrator search"
          />
        </div>
        {operatorsQuery.error ? <InlineNotice tone="danger">{operatorsQuery.error.message}</InlineNotice> : null}
        <DataTable
          columns={columns}
          rows={operators}
          rowKey={(operator) => operator.id}
          onRowClick={setSelected}
          emptyText={query ? "No administrators match this search." : "No administrator accounts found."}
          loading={operatorsQuery.isLoading || operatorsQuery.isFetching}
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
        open={creating || Boolean(selected)}
        title={creating ? "Add administrator" : selected?.displayName || ""}
        subtitle={creating ? "Create a monitoring console account" : `@${selected?.username}`}
        onClose={() => { setCreating(false); setSelected(null); }}
      >
        <section className="drawer-section operator-editor">
          <div className="operator-editor-heading">
            <span><ShieldCheck size={18} /></span>
            <div>
              <h3>{creating ? "Account details" : "Access settings"}</h3>
              <p>{creating ? "The account can sign in immediately after creation." : "Changes apply to the next authenticated request."}</p>
            </div>
          </div>
          <OperatorForm
            key={creating ? "create" : selected?.id}
            operator={creating ? null : selected}
            onSaved={refresh}
            onCancel={() => { setCreating(false); setSelected(null); }}
          />
        </section>
      </DetailDrawer>
    </>
  );
}
