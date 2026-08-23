import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { RefreshCw, RotateCcw, Undo2 } from "lucide-react";
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

const PAGE_SIZE = 30;
const FAILURE_PAGE_SIZE = 20;
const STATUSES = ["", "WAITING", "COMPLETED", "FAILED"];
const FAILURE_SOURCES = ["", "REVENUECAT_EVENT", "SUBSCRIPTION_RECONCILIATION"];
const FAILURE_STATUSES = ["", "RETRYING", "EXHAUSTED"];

function amount(invoice) {
  if (invoice.priceMilliunits == null || !invoice.currency) return "-";
  const value = Number(invoice.priceMilliunits) / 1_000_000;
  if (!Number.isFinite(value)) return "-";
  try {
    const formatted = new Intl.NumberFormat("ko-KR", {
      style: "currency",
      currency: invoice.currency,
      maximumFractionDigits: invoice.currency === "KRW" ? 0 : 2,
    }).format(value);
    return invoice.type === "REFUND" ? `-${formatted}` : formatted;
  } catch {
    return `${value.toLocaleString()} ${invoice.currency}`;
  }
}

function ActionForm({ invoice, onCompleted }) {
  const [reason, setReason] = useState("");
  const mutation = useMutation({
    mutationFn: (type) => adminFetch(
      `/billing/invoices/${invoice.id}/${type === "REFUND" ? "refund-requests" : "cancellation-requests"}`,
      {
        method: "POST",
        body: JSON.stringify({
          idempotencyKey: `admin-${type.toLowerCase()}-${crypto.randomUUID()}`,
          reason: reason.trim() || null,
        }),
      },
    ),
    onSuccess: onCompleted,
  });
  const refundable = (invoice.type || "NORMAL") === "NORMAL"
    && invoice.status === "COMPLETED"
    && ["SETTLED", "REFUND_DECLINED", "REFUND_REVERSED"].includes(invoice.paymentStatus)
    && Boolean(invoice.paymentId);
  const cancellable = (invoice.type || "NORMAL") === "NORMAL" && invoice.status === "COMPLETED"
    && Boolean(invoice.originalTransactionId);

  return (
    <section className="drawer-section order-action-section">
      <h3>Order actions</h3>
      <p className="section-description">
        This records an audited request. Apple confirms the final cancellation or refund through a signed server notification.
      </p>
      <label className="field">
        <span>Reason</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value.slice(0, 1000))}
          placeholder="Internal reason for the request"
        />
      </label>
      {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
      <div className="drawer-form-actions">
        <Button
          variant="secondary"
          icon={RotateCcw}
          busy={mutation.isPending && mutation.variables === "CANCELLATION"}
          disabled={!cancellable || mutation.isPending}
          onClick={() => {
            if (window.confirm("Request cancellation for this Apple subscription?")) mutation.mutate("CANCELLATION");
          }}
        >
          Request cancellation
        </Button>
        <Button
          icon={Undo2}
          busy={mutation.isPending && mutation.variables === "REFUND"}
          disabled={!refundable || mutation.isPending}
          onClick={() => {
            if (window.confirm("Start the audited refund workflow for this payment?")) mutation.mutate("REFUND");
          }}
        >
          Start refund
        </Button>
      </div>
    </section>
  );
}

function OrderDetail({ selected, onClose }) {
  const queryClient = useQueryClient();
  const detailQuery = useQuery({
    queryKey: ["admin", "billing", "invoice", selected?.invoice?.id],
    queryFn: () => adminFetch(`/billing/invoices/${selected.invoice.id}`),
    enabled: Boolean(selected?.invoice?.id),
  });
  const detail = detailQuery.data;
  const invoice = detail?.detail?.invoice || selected?.invoice;
  const events = Array.isArray(detail?.detail?.events) ? detail.detail.events : [];
  const paymentHistory = Array.isArray(detail?.detail?.paymentHistory) ? detail.detail.paymentHistory : [];
  const actions = Array.isArray(detail?.detail?.actions) ? detail.detail.actions : [];

  const eventColumns = useMemo(() => [
    { key: "sequenceNumber", label: "Seq", className: "mono" },
    { key: "eventType", label: "Invoice event" },
    { key: "transition", label: "Transition", render: (event) => `${event.fromStatus || "-"} → ${event.toStatus}` },
    { key: "source", label: "Source" },
    { key: "occurredAt", label: "Occurred", render: (event) => formatDateTime(event.occurredAt) },
  ], []);
  const paymentColumns = useMemo(() => [
    { key: "eventType", label: "Payment event" },
    { key: "transition", label: "Transition", render: (event) => `${event.fromStatus || "-"} → ${event.toStatus}` },
    { key: "source", label: "Source" },
    { key: "reason", label: "Reason", render: (event) => event.reason || "-" },
    { key: "occurredAt", label: "Occurred", render: (event) => formatDateTime(event.occurredAt) },
  ], []);
  const actionColumns = useMemo(() => [
    { key: "actionType", label: "Action" },
    { key: "status", label: "Status", render: (action) => <StatusBadge tone={statusTone(action.status)}>{action.status}</StatusBadge> },
    { key: "reason", label: "Reason", render: (action) => action.reason || "-" },
    { key: "requestedAt", label: "Requested", render: (action) => formatDateTime(action.requestedAt) },
  ], []);

  async function refreshAfterAction() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["admin", "billing", "invoices"] }),
      queryClient.invalidateQueries({ queryKey: ["admin", "billing", "invoice", invoice.id] }),
    ]);
  }

  return (
    <DetailDrawer
      open={Boolean(selected)}
      title={invoice ? `Invoice #${invoice.id}` : "Invoice"}
      subtitle={detail ? `${detail.userDisplayName} · ${detail.userEmail || `User ${detail.userId}`}` : selected?.userDisplayName}
      onClose={onClose}
    >
      {detailQuery.error ? <InlineNotice tone="danger">{detailQuery.error.message}</InlineNotice> : null}
      {!invoice || detailQuery.isLoading ? <div className="table-state"><RefreshCw className="spin" size={18} /> Loading order...</div> : (
        <>
          <div className="detail-summary">
            <div><span>Status</span><strong><StatusBadge tone={statusTone(invoice.status)}>{invoice.status}</StatusBadge></strong></div>
            <div><span>Type</span><strong>{invoice.type || "NORMAL"}</strong></div>
            <div><span>Tier</span><strong>{invoice.tierCode}</strong></div>
            <div><span>Amount</span><strong>{amount(invoice)}</strong></div>
            <div><span>Purchased</span><strong>{formatDateTime(invoice.purchaseAt || invoice.createdAt)}</strong></div>
          </div>
          <section className="drawer-section order-identifiers">
            <h3>Provider identifiers</h3>
            <dl>
              <div><dt>Invoice number</dt><dd>{invoice.invoiceNumber}</dd></div>
              <div><dt>Original invoice</dt><dd>{invoice.originalInvoiceId || "-"}</dd></div>
              <div><dt>Transaction</dt><dd>{invoice.transactionId || "-"}</dd></div>
              <div><dt>Original transaction</dt><dd>{invoice.originalTransactionId || "-"}</dd></div>
              <div><dt>Product</dt><dd>{invoice.productId}</dd></div>
              <div><dt>Expires</dt><dd>{formatDateTime(invoice.expiresAt)}</dd></div>
            </dl>
          </section>
          <section className="drawer-section order-ledger-section">
            <h3>Invoice event ledger</h3>
            <DataTable columns={eventColumns} rows={events} rowKey={(event) => event.eventId} emptyText="No invoice events." />
          </section>
          <section className="drawer-section order-ledger-section">
            <h3>Payment history</h3>
            <DataTable columns={paymentColumns} rows={paymentHistory} rowKey={(event) => event.eventId} emptyText="No payment history." />
          </section>
          <section className="drawer-section order-ledger-section">
            <h3>Cancellation and refund actions</h3>
            <DataTable columns={actionColumns} rows={actions} rowKey={(action) => action.actionId} emptyText="No order actions." />
          </section>
          <ActionForm invoice={invoice} onCompleted={refreshAfterAction} />
        </>
      )}
    </DetailDrawer>
  );
}

function OrdersWorkspace() {
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
  if (query) params.set("query", query);
  if (status) params.set("status", status);
  const ordersQuery = useQuery({
    queryKey: ["admin", "billing", "invoices", query, status, offset],
    queryFn: () => adminFetch(`/billing/invoices?${params}`),
    placeholderData: keepPreviousData,
  });
  const rows = Array.isArray(ordersQuery.data?.invoices) ? ordersQuery.data.invoices : [];
  const total = Number(ordersQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "invoice",
      label: "Invoice",
      render: (row) => (
        <div className="primary-cell">
          <strong>#{row.invoice.id} · {row.invoice.type || "NORMAL"} · {row.invoice.tierCode}</strong>
          <span className="mono">{row.invoice.invoiceNumber}</span>
        </div>
      ),
    },
    {
      key: "user",
      label: "User",
      render: (row) => (
        <div className="primary-cell">
          <strong>{row.userDisplayName}</strong>
          <span>{row.userEmail || `User ${row.userId}`}</span>
        </div>
      ),
    },
    { key: "status", label: "Status", render: (row) => <StatusBadge tone={statusTone(row.invoice.status)}>{row.invoice.status}</StatusBadge> },
    { key: "payment", label: "Payment", render: (row) => row.invoice.paymentStatus ? <StatusBadge tone={statusTone(row.invoice.paymentStatus)}>{row.invoice.paymentStatus}</StatusBadge> : "-" },
    { key: "amount", label: "Amount", render: (row) => amount(row.invoice) },
    { key: "transaction", label: "Transaction", className: "mono", render: (row) => row.invoice.transactionId || "-" },
    { key: "createdAt", label: "Created", render: (row) => formatDateTime(row.invoice.createdAt) },
  ], []);

  return (
    <>
      {ordersQuery.error ? <InlineNotice tone="danger">{ordersQuery.error.message}</InlineNotice> : null}
      <section className="workspace-section">
        <div className="section-heading toolbar-heading">
          <div>
            <h2>Orders</h2>
            <p>{total.toLocaleString()} invoice records · Apple is the settlement source of truth.</p>
          </div>
          <div className="inline-controls order-filters">
            <label className="field compact-field">
              <span>Status</span>
              <select value={status} onChange={(event) => { setStatus(event.target.value); setOffset(0); }}>
                {STATUSES.map((value) => <option key={value || "ALL"} value={value}>{value || "All statuses"}</option>)}
              </select>
            </label>
            <SearchField
              value={draftQuery}
              onChange={setDraftQuery}
              onSubmit={() => { setQuery(draftQuery.trim()); setOffset(0); }}
              placeholder="Invoice, transaction, user, or email"
              label="Order search"
            />
          </div>
        </div>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.invoice.id}
          onRowClick={setSelected}
          emptyText="No orders match this filter."
          loading={ordersQuery.isLoading}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}` : "0 orders"}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>
      <OrderDetail selected={selected} onClose={() => setSelected(null)} />
    </>
  );
}

function ProcessingFailureDetail({ failure, onClose }) {
  return (
    <DetailDrawer
      open={Boolean(failure)}
      title={failure ? `${failure.source} failure` : "Billing processing failure"}
      subtitle={failure ? `${failure.status} · attempt ${failure.attemptCount} of ${failure.maxAttempts}` : null}
      onClose={onClose}
    >
      {failure ? (
        <>
          <div className="detail-summary">
            <div><span>Status</span><strong><StatusBadge tone={statusTone(failure.status)}>{failure.status}</StatusBadge></strong></div>
            <div><span>Attempts</span><strong>{failure.attemptCount} / {failure.maxAttempts}</strong></div>
            <div><span>User</span><strong>{failure.userDisplayName || failure.userEmail || failure.userId || "-"}</strong></div>
            <div><span>Next retry</span><strong>{formatDateTime(failure.nextAttemptAt)}</strong></div>
            <div><span>Updated</span><strong>{formatDateTime(failure.updatedAt)}</strong></div>
          </div>
          <section className="drawer-section order-identifiers">
            <h3>Processing identifiers</h3>
            <dl>
              <div><dt>Event ID</dt><dd>{failure.eventId}</dd></div>
              <div><dt>Event type</dt><dd>{failure.eventType}</dd></div>
              <div><dt>Transaction</dt><dd>{failure.transactionId || "-"}</dd></div>
              <div><dt>Original transaction</dt><dd>{failure.originalTransactionId || "-"}</dd></div>
              <div><dt>Product</dt><dd>{failure.productId || "-"}</dd></div>
              <div><dt>First occurred</dt><dd>{formatDateTime(failure.occurredAt)}</dd></div>
            </dl>
          </section>
          <section className="drawer-section">
            <h3>Last error</h3>
            <pre className="object-inspector">{failure.lastError}</pre>
          </section>
        </>
      ) : null}
    </DetailDrawer>
  );
}

function ProcessingFailuresWorkspace() {
  const [source, setSource] = useState("");
  const [status, setStatus] = useState("");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const params = new URLSearchParams({ limit: String(FAILURE_PAGE_SIZE), offset: String(offset) });
  if (source) params.set("source", source);
  if (status) params.set("status", status);
  const failuresQuery = useQuery({
    queryKey: ["admin", "billing", "processing-failures", source, status, offset],
    queryFn: () => adminFetch(`/billing/processing-failures?${params}`),
    placeholderData: keepPreviousData,
  });
  const rows = Array.isArray(failuresQuery.data?.failures) ? failuresQuery.data.failures : [];
  const total = Number(failuresQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / FAILURE_PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / FAILURE_PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "status",
      label: "Status",
      render: (row) => <StatusBadge tone={statusTone(row.status)}>{row.status}</StatusBadge>,
    },
    { key: "source", label: "Source" },
    { key: "event", label: "Event", render: (row) => <div className="primary-cell"><strong>{row.eventType}</strong><span className="mono">{row.eventId}</span></div> },
    { key: "attempts", label: "Attempts", render: (row) => `${row.attemptCount} / ${row.maxAttempts}` },
    { key: "user", label: "User", render: (row) => row.userDisplayName || row.userEmail || row.userId || "-" },
    { key: "transaction", label: "Transaction", className: "mono", render: (row) => row.transactionId || row.originalTransactionId || "-" },
    { key: "nextAttemptAt", label: "Next retry", render: (row) => formatDateTime(row.nextAttemptAt) },
    { key: "updatedAt", label: "Updated", render: (row) => formatDateTime(row.updatedAt) },
  ], []);

  return (
    <>
      {failuresQuery.error ? <InlineNotice tone="danger">{failuresQuery.error.message}</InlineNotice> : null}
      <section className="workspace-section">
        <div className="section-heading toolbar-heading">
          <div>
            <h2>Billing processing failures</h2>
            <p>{total.toLocaleString()} retrying or exhausted RevenueCat and subscription reconciliation records.</p>
          </div>
          <div className="inline-controls order-filters">
            <label className="field compact-field">
              <span>Source</span>
              <select value={source} onChange={(event) => { setSource(event.target.value); setOffset(0); }}>
                {FAILURE_SOURCES.map((value) => <option key={value || "ALL"} value={value}>{value || "All sources"}</option>)}
              </select>
            </label>
            <label className="field compact-field">
              <span>Status</span>
              <select value={status} onChange={(event) => { setStatus(event.target.value); setOffset(0); }}>
                {FAILURE_STATUSES.map((value) => <option key={value || "ALL"} value={value}>{value || "All statuses"}</option>)}
              </select>
            </label>
          </div>
        </div>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => `${row.source}-${row.id}`}
          onRowClick={setSelected}
          emptyText="No billing processing failures match this filter."
          loading={failuresQuery.isLoading}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + FAILURE_PAGE_SIZE, total)} of ${total}` : "0 failures"}
          onPrevious={() => setOffset(Math.max(0, offset - FAILURE_PAGE_SIZE))}
          onNext={() => setOffset(offset + FAILURE_PAGE_SIZE)}
        />
      </section>
      <ProcessingFailureDetail failure={selected} onClose={() => setSelected(null)} />
    </>
  );
}

export function OrdersPage() {
  const queryClient = useQueryClient();
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="Orders & billing"
        description="Inspect invoices, Apple payment history, processing failures, and audited cancellation or refund workflows."
        actions={(
          <Button
            variant="secondary"
            icon={RefreshCw}
            onClick={() => queryClient.invalidateQueries({ queryKey: ["admin", "billing"] })}
          >
            Refresh
          </Button>
        )}
      />
      <OrdersWorkspace />
      <ProcessingFailuresWorkspace />
    </>
  );
}
