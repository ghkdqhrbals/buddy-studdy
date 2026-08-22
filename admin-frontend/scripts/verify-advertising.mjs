import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "..");
const panelSource = fs.readFileSync(path.join(root, "src", "AdvertisingPanel.tsx"), "utf8");
const apiSource = fs.readFileSync(path.join(root, "src", "api.ts"), "utf8");
const typeSource = fs.readFileSync(path.join(root, "src", "types.ts"), "utf8");
const chartSource = fs.readFileSync(path.join(root, "src", "Charts.tsx"), "utf8");
const styleSource = fs.readFileSync(path.join(root, "src", "styles.css"), "utf8");

const checks = [
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
