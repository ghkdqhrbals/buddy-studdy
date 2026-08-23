import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "..");
const panelSource = fs.readFileSync(path.join(root, "src", "AdvertisingPanel.tsx"), "utf8");
const apiSource = fs.readFileSync(path.join(root, "src", "api.ts"), "utf8");
const typeSource = fs.readFileSync(path.join(root, "src", "types.ts"), "utf8");
const chartSource = fs.readFileSync(path.join(root, "src", "Charts.tsx"), "utf8");
const styleSource = fs.readFileSync(path.join(root, "src", "styles.css"), "utf8");
const campaignListApiSource = apiSource.slice(
  apiSource.indexOf("export function fetchNativeAdvertisementCampaigns"),
  apiSource.indexOf("export function createNativeAdvertisementCampaign"),
);
const campaignListPanelSource = panelSource.slice(0, panelSource.indexOf("function AudienceActivity"));
const audienceActivitySource = panelSource.slice(
  panelSource.indexOf("function AudienceActivity"),
  panelSource.indexOf("function UserIdentity"),
);

const checks = [
  {
    ok: campaignListApiSource.includes('params.set("query", filters.query.trim())')
      && campaignListApiSource.includes('params.set("status", filters.status)')
      && campaignListApiSource.includes('params.set("audience", filters.audience)'),
    message: "Campaign list requests must preserve optional query, status, and audience filters.",
  },
  {
    ok: typeSource.includes('NativeAdvertisementCampaignStatusFilter = "" | "ACTIVE" | "PAUSED" | "SCHEDULED" | "ENDED"')
      && typeSource.includes('NativeAdvertisementCampaignAudienceFilter = "" | NativeAdvertisementAudience'),
    message: "Campaign list filter types must mirror the backend status and audience contract.",
  },
  {
    ok: campaignListPanelSource.includes('role="search" aria-label="Filter advertising campaigns"')
      && campaignListPanelSource.includes('htmlFor="ad-campaign-query"')
      && campaignListPanelSource.includes("Reset filters"),
    message: "Campaign filters must provide a labelled search form and an explicit reset action.",
  },
  {
    ok: campaignListPanelSource.includes("function applyCampaignSearch")
      && campaignListPanelSource.includes("function changeCampaignStatus")
      && campaignListPanelSource.includes("function changeCampaignAudience")
      && campaignListPanelSource.includes("function resetCampaignFilters")
      && /\[offset, refreshKey, appliedCampaignQuery, campaignStatusFilter, campaignAudienceFilter\]/.test(campaignListPanelSource),
    message: "Campaign filter changes must reset pagination and remain applied while paging or refreshing.",
  },
  {
    ok: campaignListPanelSource.includes("No campaigns match the current filters")
      && campaignListPanelSource.includes("Updating campaign results"),
    message: "Campaign list loading and filtered-empty states must explain the current result state.",
  },
  {
    ok: campaignListPanelSource.includes('disabled={saving} value={campaignStatusFilter}')
      && campaignListPanelSource.includes('disabled={saving} value={campaignAudienceFilter}')
      && campaignListPanelSource.includes('type="submit" disabled={saving}>Search'),
    message: "Campaign filters must stay stable while a campaign save request is in flight.",
  },
  {
    ok: campaignListPanelSource.includes("const campaignViewRef = useRef<CampaignView>")
      && campaignListPanelSource.includes("campaignRequestIdRef.current += 1")
      && campaignListPanelSource.includes("setCampaigns([])")
      && /function applyCampaignSearch[\s\S]*?invalidateCampaignResults\(0, filters\)[\s\S]*?setAppliedCampaignQuery/.test(campaignListPanelSource)
      && /function changeCampaignStatus[\s\S]*?invalidateCampaignResults\(0,[\s\S]*?setCampaignStatusFilter/.test(campaignListPanelSource)
      && /function changeCampaignAudience[\s\S]*?invalidateCampaignResults\(0,[\s\S]*?setCampaignAudienceFilter/.test(campaignListPanelSource)
      && /function changeCampaignOffset[\s\S]*?invalidateCampaignResults\(nextOffset,[\s\S]*?setOffset/.test(campaignListPanelSource),
    message: "Campaign filter and page changes must immediately invalidate and clear stale list responses.",
  },
  {
    ok: campaignListPanelSource.includes("const latestView = campaignViewRef.current")
      && campaignListPanelSource.includes("await loadCampaigns(refreshedView.offset, refreshedView.filters)")
      && !/await loadCampaigns\(nextOffset,\s*\{\s*query: appliedCampaignQuery/.test(campaignListPanelSource),
    message: "Campaign saves must refresh the latest filter view instead of a pre-save closure.",
  },
  {
    ok: campaignListPanelSource.includes('role="status" aria-live="polite" aria-atomic="true">{campaignResultAnnouncement}')
      && campaignListPanelSource.includes("campaign results loaded."),
    message: "Completed campaign searches must announce their result count through a polite live status.",
  },
  {
    ok: apiSource.includes("/native-ad-campaigns/${campaignId}/users"),
    message: "Advertising must load per-campaign user activity from the nested users endpoint.",
  },
  {
    ok: /params\.set\("query",\s*query\.trim\(\)\)/.test(apiSource)
      && /params\.set\("status",\s*status\)/.test(apiSource),
    message: "Campaign user activity must preserve search and open-status filters.",
  },
  {
    ok: audienceActivitySource.includes("const userRequestIdRef = useRef(0)")
      && audienceActivitySource.includes("requestId !== userRequestIdRef.current")
      && audienceActivitySource.includes("function invalidateUserResults()")
      && /function selectCampaign[\s\S]*?invalidateUserResults\(\);[\s\S]*?onCampaignChange/.test(audienceActivitySource)
      && /function applySearch[\s\S]*?invalidateUserResults\(\);[\s\S]*?setAppliedQuery/.test(audienceActivitySource)
      && /function changeStatus[\s\S]*?invalidateUserResults\(\);[\s\S]*?setStatus/.test(audienceActivitySource)
      && /function changeUserOffset[\s\S]*?invalidateUserResults\(\);[\s\S]*?setOffset/.test(audienceActivitySource),
    message: "Campaign changes must invalidate stale audience-activity responses.",
  },
  {
    ok: audienceActivitySource.includes("const pageIsCurrent = loadedViewKey === currentViewKey")
      && audienceActivitySource.includes("const visiblePage = pageIsCurrent ? page : emptyUserPage")
      && audienceActivitySource.includes("setLoadedViewKey(viewKey)")
      && /catch \(cause\)[\s\S]*?setLoadedViewKey\(viewKey\)[\s\S]*?setError/.test(audienceActivitySource)
      && audienceActivitySource.includes("visiblePage.users.map")
      && !audienceActivitySource.includes("{page.users.map"),
    message: "Audience activity must only render a response owned by the current campaign and filter view.",
  },
  {
    ok: audienceActivitySource.includes('role="status" aria-live="polite" aria-atomic="true">{userResultAnnouncement}')
      && audienceActivitySource.includes("audience activity results loaded."),
    message: "Completed audience searches must announce their result count through a polite live status.",
  },
  {
    ok: panelSource.includes("Feed deliveries are server-added placements")
      && panelSource.includes("not proof that the external page loaded"),
    message: "Advertising metrics must explain feed-delivery and destination-open semantics.",
  },
  {
    ok: panelSource.includes("New campaign")
      && panelSource.includes("Audience")
      && panelSource.includes("Edit"),
    message: "Campaign list actions must be explicit and keyboard-accessible.",
  },
  {
    ok: panelSource.includes("isSupportedDestination")
      && panelSource.includes("integerInRange(form.dailySelectionCap, 0, 100)")
      && panelSource.includes("integerInRange(form.latestPosition, 0, 99)"),
    message: "Campaign validation must mirror backend destination and delivery constraints.",
  },
  {
    ok: /accountStatus:\s*"ACTIVE" \| "ANONYMOUS" \| "PENDING_TERMS" \| "WITHDRAWN"/.test(typeSource),
    message: "Campaign user activity must account for every backend user state.",
  },
  {
    ok: !/NativeAdvertisementUserSummary\s*=\s*\{[^}]*deviceId/s.test(typeSource),
    message: "Campaign user activity must not expose raw device identifiers.",
  },
  {
    ok: (chartSource.match(/\(clientX - bounds\.left\) \/ Math\.max\(1, bounds\.width\)/g) ?? []).length === 2
      && chartSource.includes("aria-valuetext")
      && chartSource.includes("ResizeObserver"),
    message: "Charts must use responsive plot geometry with accurate pointer and screen-reader values.",
  },
  {
    ok: /@media \(max-width: 760px\)[\s\S]*?\.sidebar \{[\s\S]*?position: static;/.test(styleSource)
      && styleSource.includes(".trend-canvas.combined-canvas svg"),
    message: "Mobile navigation and the enlarged combined chart must keep their responsive overrides.",
  },
];

const failures = checks.filter((check) => !check.ok).map((check) => check.message);
if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log("Advertising campaign usability and user-activity contracts verified.");
