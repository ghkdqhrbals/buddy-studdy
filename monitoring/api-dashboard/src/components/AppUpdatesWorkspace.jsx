import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw, Rocket, ShieldAlert, Square } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import { formatDateTime } from "../lib/format.js";
import {
  DataTable,
  DetailDrawer,
  Pagination,
  SearchField,
  SegmentedTabs,
  StatusBadge,
} from "./AdminUI.jsx";
import { Button } from "./Button.jsx";
import { InlineNotice } from "./InlineNotice.jsx";

const PAGE_SIZE = 20;
const DEFAULT_APP_STORE_URL = "https://apps.apple.com/app/id6774108938";
const DEFAULT_FORM = {
  platform: "ios",
  targetVersion: "",
  targetBuild: "",
  mode: "OPTIONAL",
  titleKo: "새 버전이 준비됐어요",
  titleEn: "A new version is ready",
  titleJa: "新しいバージョンがあります",
  messageKo: "더 안정적인 학습 경험을 위해 앱을 업데이트해 주세요.",
  messageEn: "Update the app for a more reliable study experience.",
  messageJa: "より安定した学習体験のため、アプリをアップデートしてください。",
  appStoreUrl: DEFAULT_APP_STORE_URL,
};

function publicationTone(status) {
  if (status === "PUBLISHED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "PENDING") return "warning";
  return "neutral";
}

function conversionRate(campaign) {
  if (!campaign || Number(campaign.promptedUserCount) <= 0) return "—";
  return `${(Number(campaign.conversionRate || 0) * 100).toFixed(1)}%`;
}

function UpdateCampaignForm({ onCreated, onChanged }) {
  const [form, setForm] = useState(() => ({ ...DEFAULT_FORM }));
  const createMutation = useMutation({
    mutationFn: () => adminFetch("/app-updates", {
      method: "POST",
      body: JSON.stringify({
        ...form,
        targetVersion: form.targetVersion.trim(),
        targetBuild: form.targetBuild.trim(),
        appStoreUrl: form.appStoreUrl.trim(),
      }),
    }),
    onSuccess: (created) => {
      setForm((current) => ({ ...current, targetVersion: "", targetBuild: "" }));
      onCreated(created);
    },
  });
  const republishMutation = useMutation({
    mutationFn: () => adminFetch("/app-updates/remote-config/publish", { method: "POST" }),
    onSuccess: onChanged,
  });
  const error = createMutation.error || republishMutation.error;
  const busy = createMutation.isPending || republishMutation.isPending;
  const disabled = !form.targetVersion.trim()
    || !form.targetBuild.trim()
    || !form.appStoreUrl.trim()
    || [
      form.titleKo,
      form.titleEn,
      form.titleJa,
      form.messageKo,
      form.messageEn,
      form.messageJa,
    ].some((value) => !value.trim());

  function update(key, value) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  return (
    <section className="workspace-section app-update-form-section">
      <div className="section-heading mode-heading">
        <div>
          <h2>Publish update guidance</h2>
          <p>Only devices below the target version and build receive this Firebase Remote Config policy.</p>
        </div>
        <SegmentedTabs
          value={form.mode}
          onChange={(mode) => update("mode", mode)}
          ariaLabel="Update prompt behavior"
          items={[
            { value: "OPTIONAL", label: "Recommended · dismissible" },
            { value: "FORCE", label: "Required · blocking" },
          ]}
        />
      </div>
      <div className="app-update-form-body">
        <div className="app-update-target-grid">
          <label className="field">
            <span>Target version</span>
            <input
              value={form.targetVersion}
              placeholder="1.1.0"
              onChange={(event) => update("targetVersion", event.target.value)}
            />
          </label>
          <label className="field">
            <span>Target build</span>
            <input
              value={form.targetBuild}
              inputMode="numeric"
              placeholder="71"
              onChange={(event) => update("targetBuild", event.target.value)}
            />
          </label>
          <label className="field app-update-store-field">
            <span>App Store URL</span>
            <input value={form.appStoreUrl} onChange={(event) => update("appStoreUrl", event.target.value)} />
          </label>
        </div>

        <div className="maintenance-language-grid">
          {[
            ["Korean", "titleKo", "messageKo"],
            ["English", "titleEn", "messageEn"],
            ["Japanese", "titleJa", "messageJa"],
          ].map(([label, titleKey, messageKey]) => (
            <fieldset className="maintenance-language" key={label}>
              <legend>{label}</legend>
              <label className="field">
                <span>Title</span>
                <input
                  maxLength={120}
                  value={form[titleKey]}
                  onChange={(event) => update(titleKey, event.target.value)}
                />
              </label>
              <label className="field">
                <span>Message</span>
                <textarea
                  maxLength={1000}
                  rows={4}
                  value={form[messageKey]}
                  onChange={(event) => update(messageKey, event.target.value)}
                />
              </label>
            </fieldset>
          ))}
        </div>

        {form.mode === "FORCE" ? (
          <InlineNotice tone="danger" compact>
            Required updates cannot be dismissed. Confirm the target build is available in the App Store before publishing.
          </InlineNotice>
        ) : null}
        {error ? <InlineNotice tone="danger" compact>{error.message}</InlineNotice> : null}
        <div className="app-update-form-actions">
          <Button
            variant="secondary"
            icon={RefreshCw}
            busy={republishMutation.isPending}
            disabled={createMutation.isPending}
            onClick={() => republishMutation.mutate()}
          >
            Republish current policy
          </Button>
          <Button
            icon={form.mode === "FORCE" ? ShieldAlert : Rocket}
            busy={createMutation.isPending}
            disabled={busy || disabled}
            onClick={() => createMutation.mutate()}
          >
            {form.mode === "FORCE" ? "Publish required update" : "Publish recommended update"}
          </Button>
        </div>
      </div>
    </section>
  );
}

function CampaignDrawer({ campaign, onClose, onChanged }) {
  const [offset, setOffset] = useState(0);
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const usersQuery = useQuery({
    queryKey: ["admin", "app-update-users", campaign.id, query, status, offset],
    queryFn: () => {
      const parameters = new URLSearchParams({
        limit: String(PAGE_SIZE),
        offset: String(offset),
      });
      if (query) parameters.set("query", query);
      if (status) parameters.set("status", status);
      return adminFetch(`/app-updates/${campaign.id}/users?${parameters}`);
    },
  });
  const endMutation = useMutation({
    mutationFn: () => adminFetch(`/app-updates/${campaign.id}/end`, { method: "POST" }),
    onSuccess: onChanged,
  });
  const users = Array.isArray(usersQuery.data?.users) ? usersQuery.data.users : [];
  const total = Number(usersQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "user",
      label: "User",
      render: (row) => (
        <div className="primary-cell">
          <strong>{row.displayName || `User ${row.userId}`}</strong>
          <span>{row.email || row.deviceId}</span>
        </div>
      ),
    },
    {
      key: "version",
      label: "Current version",
      render: (row) => (
        <div className="primary-cell">
          <strong>{row.currentVersion}</strong>
          <span>build {row.currentBuild}</span>
        </div>
      ),
    },
    { key: "firstVersion", label: "First seen", render: (row) => `${row.firstVersion} (${row.firstBuild})` },
    {
      key: "status",
      label: "State",
      render: (row) => <StatusBadge tone={row.status === "CONVERTED" ? "success" : "neutral"}>{row.status}</StatusBadge>,
    },
    { key: "lastCheckedAt", label: "Last check", render: (row) => formatDateTime(row.lastCheckedAt) },
  ], []);

  return (
    <DetailDrawer
      open
      title={`iOS ${campaign.targetVersion} (${campaign.targetBuild})`}
      subtitle="Per-device update funnel and current installed version"
      onClose={onClose}
    >
      <div className="detail-summary">
        <div><span>Mode</span><strong>{campaign.mode}</strong></div>
        <div><span>Prompted</span><strong>{campaign.promptedUserCount}</strong></div>
        <div><span>Converted</span><strong>{campaign.convertedUserCount}</strong></div>
        <div><span>Conversion</span><strong>{conversionRate(campaign)}</strong></div>
      </div>
      <section className="drawer-section">
        <h3>Campaign controls</h3>
        <p className="section-description">
          Remote Config · {campaign.remoteConfigStatus}
          {campaign.remoteConfigPublishedAt ? ` · ${formatDateTime(campaign.remoteConfigPublishedAt)}` : ""}
        </p>
        {campaign.remoteConfigError ? (
          <InlineNotice tone="danger" compact>{campaign.remoteConfigError}</InlineNotice>
        ) : null}
        {endMutation.error ? <InlineNotice tone="danger" compact>{endMutation.error.message}</InlineNotice> : null}
        {campaign.status === "ACTIVE" ? (
          <div className="drawer-form-actions">
            <Button
              variant="secondary"
              icon={Square}
              busy={endMutation.isPending}
              onClick={() => {
                if (globalThis.confirm("End this update campaign and republish Firebase Remote Config?")) {
                  endMutation.mutate();
                }
              }}
            >
              End campaign
            </Button>
          </div>
        ) : null}
      </section>
      <section className="drawer-section app-update-users-section">
        <h3>User conversion</h3>
        <div className="app-update-user-filters">
          <SearchField
            value={queryDraft}
            onChange={setQueryDraft}
            onSubmit={() => {
              setOffset(0);
              setQuery(queryDraft.trim());
            }}
            label="Campaign user search"
            placeholder="Email, name, or user ID"
          />
          <label className="field compact-field">
            <span>State</span>
            <select
              value={status}
              onChange={(event) => {
                setOffset(0);
                setStatus(event.target.value);
              }}
            >
              <option value="">All states</option>
              <option value="CONVERTED">Converted</option>
              <option value="OPENED">Store opened</option>
              <option value="DISMISSED">Dismissed</option>
              <option value="PROMPTED">Prompted</option>
              <option value="CHECKED">Checked only</option>
            </select>
          </label>
        </div>
        {usersQuery.error ? <InlineNotice tone="danger" compact>{usersQuery.error.message}</InlineNotice> : null}
        <DataTable
          columns={columns}
          rows={users}
          rowKey={(row) => `${row.userId}-${row.deviceId}`}
          emptyText="No devices have entered this campaign."
          loading={usersQuery.isLoading || usersQuery.isFetching}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}` : "0 devices"}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>
    </DetailDrawer>
  );
}

export function AppUpdatesWorkspace() {
  const queryClient = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const campaignQuery = useQuery({
    queryKey: ["admin", "app-update-campaigns", offset],
    queryFn: () => adminFetch(`/app-updates?limit=${PAGE_SIZE}&offset=${offset}`),
    refetchInterval: 10_000,
  });
  const campaigns = Array.isArray(campaignQuery.data?.campaigns) ? campaignQuery.data.campaigns : [];
  const total = Number(campaignQuery.data?.totalCount) || 0;
  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns = useMemo(() => [
    {
      key: "target",
      label: "Target",
      render: (row) => (
        <div className="primary-cell">
          <strong>{row.targetVersion}</strong>
          <span>build {row.targetBuild}</span>
        </div>
      ),
    },
    {
      key: "mode",
      label: "Mode",
      render: (row) => <StatusBadge tone={row.mode === "FORCE" ? "danger" : "neutral"}>{row.mode}</StatusBadge>,
    },
    {
      key: "status",
      label: "Status",
      render: (row) => <StatusBadge tone={row.status === "ACTIVE" ? "success" : "neutral"}>{row.status}</StatusBadge>,
    },
    {
      key: "remoteConfigStatus",
      label: "Remote Config",
      render: (row) => <StatusBadge tone={publicationTone(row.remoteConfigStatus)}>{row.remoteConfigStatus}</StatusBadge>,
    },
    {
      key: "audience",
      label: "Prompted / converted",
      render: (row) => `${row.promptedUserCount} / ${row.convertedUserCount}`,
    },
    { key: "rate", label: "Conversion", render: conversionRate },
    { key: "activatedAt", label: "Activated", render: (row) => formatDateTime(row.activatedAt) },
  ], []);

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["admin", "app-update-campaigns"] });
  }

  function handleCreated(created) {
    setOffset(0);
    setSelected(created);
    refresh();
  }

  return (
    <>
      {campaignQuery.error ? <InlineNotice tone="danger">{campaignQuery.error.message}</InlineNotice> : null}
      <UpdateCampaignForm onCreated={handleCreated} onChanged={refresh} />
      <section className="workspace-section">
        <div className="section-heading">
          <div>
            <h2>Update campaign history</h2>
            <p>Select a campaign to inspect device versions and conversion progress.</p>
          </div>
          <span className="section-count">{total.toLocaleString()} campaigns</span>
        </div>
        <DataTable
          columns={columns}
          rows={campaigns}
          rowKey={(row) => row.id}
          onRowClick={setSelected}
          emptyText="No update campaigns yet."
          loading={campaignQuery.isLoading || campaignQuery.isFetching}
        />
        <Pagination
          page={page}
          totalPages={totalPages}
          label={total ? `${Math.min(offset + 1, total)}–${Math.min(offset + PAGE_SIZE, total)} of ${total}` : "0 campaigns"}
          onPrevious={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          onNext={() => setOffset(offset + PAGE_SIZE)}
        />
      </section>
      {selected ? (
        <CampaignDrawer
          campaign={selected}
          onClose={() => setSelected(null)}
          onChanged={(campaign) => {
            setSelected(campaign);
            refresh();
          }}
        />
      ) : null}
    </>
  );
}
