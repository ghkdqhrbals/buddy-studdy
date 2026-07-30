import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, LogOut } from "lucide-react";
import { useMemo, useState } from "react";
import { AdminGate } from "../admin/AdminGate.jsx";
import { adminFetch } from "../admin/adminApi.js";
import { useAdminSession } from "../admin/AdminSessionContext.jsx";
import { AdminNotificationComposer } from "../components/AdminNotificationComposer.jsx";
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
import { formatDateTime } from "../lib/format.js";

const PAGE_SIZE = 20;
const STATUS_OPTIONS = ["ALL", "NEW", "REVIEWED", "REPLIED"];

function feedbackTone(status) {
  if (status === "NEW") return "warning";
  if (status === "REPLIED") return "success";
  return "neutral";
}

function FeedbackWorkspace() {
  const queryClient = useQueryClient();
  const [draftQuery, setDraftQuery] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("ALL");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const feedbackQuery = useQuery({
    queryKey: ["admin", "feedback", query, status, offset],
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (query) params.set("query", query);
      if (status !== "ALL") params.set("status", status);
      return adminFetch(`/feedback?${params}`);
    },
  });
  const reviewMutation = useMutation({
    mutationFn: () => adminFetch(`/feedback/${selected.id}/review`, { method: "PATCH" }),
    onSuccess: (updated) => {
      setSelected(updated);
      queryClient.invalidateQueries({ queryKey: ["admin", "feedback"] });
    },
  });
  const feedback = Array.isArray(feedbackQuery.data?.feedback) ? feedbackQuery.data.feedback : [];
  const total = Number(feedbackQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "author",
      label: "User",
      render: (item) => (
        <div className="primary-cell">
          <strong>{item.displayName || "Anonymous device"}</strong>
          <span>{item.email || item.deviceId || "No delivery target"}</span>
        </div>
      ),
    },
    {
      key: "content",
      label: "Feedback",
      render: (item) => <span className="feedback-preview">{item.content}</span>,
    },
    {
      key: "status",
      label: "Status",
      render: (item) => <StatusBadge tone={feedbackTone(item.status)}>{item.status}</StatusBadge>,
    },
    { key: "createdAt", label: "Received", render: (item) => formatDateTime(item.createdAt) },
  ], []);

  return (
    <>
      {feedbackQuery.error ? <InlineNotice tone="danger">{feedbackQuery.error.message}</InlineNotice> : null}
      <section className="workspace-section">
        <div className="section-heading toolbar-heading">
          <div><h2>Submissions</h2><p>{total.toLocaleString()} feedback messages</p></div>
          <div className="toolbar-group">
            <label className="field compact-field">
              <span>Status</span>
              <select
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value);
                  setOffset(0);
                }}
              >
                {STATUS_OPTIONS.map((value) => <option key={value}>{value}</option>)}
              </select>
            </label>
            <SearchField
              value={draftQuery}
              onChange={setDraftQuery}
              onSubmit={() => {
                setOffset(0);
                setQuery(draftQuery.trim());
              }}
              label="Feedback search"
              placeholder="Content, user, email, or ID"
            />
          </div>
        </div>
        <DataTable
          columns={columns}
          rows={feedback}
          rowKey={(item) => item.id}
          onRowClick={setSelected}
          emptyText="No feedback found."
          loading={feedbackQuery.isLoading || feedbackQuery.isFetching}
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
        title={selected?.displayName || `Feedback ${selected?.id}`}
        subtitle={selected?.email || selected?.deviceId}
        onClose={() => setSelected(null)}
      >
        {selected ? (
          <>
            <div className="detail-summary">
              <div><span>Status</span><StatusBadge tone={feedbackTone(selected.status)}>{selected.status}</StatusBadge></div>
              <div><span>Received</span><strong>{formatDateTime(selected.createdAt)}</strong></div>
              <div><span>Reviewed</span><strong>{formatDateTime(selected.reviewedAt)}</strong></div>
              <div><span>Replied</span><strong>{formatDateTime(selected.repliedAt)}</strong></div>
            </div>
            <section className="drawer-section">
              <h3>Feedback</h3>
              <p className="feedback-full-content">{selected.content}</p>
              {selected.status === "NEW" ? (
                <div className="drawer-form-actions">
                  {reviewMutation.error ? <InlineNotice tone="danger" compact>{reviewMutation.error.message}</InlineNotice> : null}
                  <Button
                    variant="secondary"
                    icon={CheckCircle2}
                    busy={reviewMutation.isPending}
                    onClick={() => reviewMutation.mutate()}
                  >
                    Mark reviewed
                  </Button>
                </div>
              ) : null}
            </section>
            {(selected.userId || selected.deviceId) ? (
              <AdminNotificationComposer
                key={`feedback-message-${selected.id}-${selected.status}`}
                endpoint={`/feedback/${selected.id}/notifications`}
                title="Reply to this feedback"
                description="Send a feedback-linked reply through the notification inbox and APNs."
                initialTitle="피드백을 확인했어요"
                initialBody="소중한 피드백 감사합니다. 내용을 확인하고 반영할게요."
                onSent={() => {
                  const updated = { ...selected, status: "REPLIED" };
                  setSelected(updated);
                  queryClient.invalidateQueries({ queryKey: ["admin", "feedback"] });
                }}
              />
            ) : <InlineNotice tone="warning">This feedback has no user or device target.</InlineNotice>}
            <ObjectInspector value={selected} title="Feedback object" />
          </>
        ) : null}
      </DetailDrawer>
    </>
  );
}

export function FeedbackPage() {
  const { authenticated, logout } = useAdminSession();
  return (
    <>
      <PageHeader
        eyebrow="Manage"
        title="User feedback"
        description="Review product feedback and send a targeted in-app message or deep-linked push."
        actions={authenticated ? <Button variant="ghost" icon={LogOut} onClick={logout}>Sign out</Button> : null}
      />
      <AdminGate><FeedbackWorkspace /></AdminGate>
    </>
  );
}
