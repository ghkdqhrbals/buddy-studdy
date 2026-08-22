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
    ok: apiSource.includes("/native-ad-campaigns/${campaignId}/users"),
    message: "Advertising must load per-campaign user activity from the nested users endpoint.",
  },
  {
    ok: /params\.set\("query",\s*query\.trim\(\)\)/.test(apiSource)
      && /params\.set\("status",\s*status\)/.test(apiSource),
    message: "Campaign user activity must preserve search and open-status filters.",
  },
  {
    ok: panelSource.includes("const userRequestIdRef = useRef(0)")
      && panelSource.includes("requestId !== userRequestIdRef.current")
      && panelSource.includes("if (campaignChanged) setPage(emptyUserPage)"),
    message: "Campaign changes must invalidate stale audience-activity responses.",
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
