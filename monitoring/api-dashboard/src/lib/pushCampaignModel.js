export const PUSH_DESTINATIONS = [
  { value: "public", label: "Public feed", deepLink: "buddystudy://public/questions" },
  { value: "public-question", label: "Public question" },
  { value: "studies", label: "My studies", deepLink: "buddystudy://studies" },
  { value: "records", label: "Records", deepLink: "buddystudy://records" },
  { value: "stats", label: "Learning statistics", deepLink: "buddystudy://statistics" },
  { value: "profile", label: "Profile", deepLink: "buddystudy://profile" },
  { value: "settings", label: "Settings", deepLink: "buddystudy://settings" },
  { value: "message", label: "Home message popup", deepLink: "buddystudy://home/message" },
];

export const MAX_SELECTED_USERS = 500;

export function campaignDeepLink(destination, questionId = "") {
  if (destination === "public-question") {
    const id = String(questionId).trim();
    return /^[1-9]\d*$/.test(id) ? `buddystudy://public/questions/${id}` : "";
  }
  return PUSH_DESTINATIONS.find((item) => item.value === destination)?.deepLink || "";
}

export function validateCampaignDraft(draft) {
  const errors = [];
  if (!draft.title.trim() || draft.title.trim().length > 160) errors.push("Enter a push title of up to 160 characters.");
  if (!draft.body.trim() || draft.body.length > 2000) errors.push("Enter a message of up to 2,000 characters.");
  if (!campaignDeepLink(draft.destination, draft.questionId)) errors.push("Choose a supported landing page and a valid public question ID.");
  if (!["ALL_REGISTERED", "SELECTED_USERS"].includes(draft.audience)) errors.push("Choose an audience.");
  if (draft.audience === "SELECTED_USERS" && (!draft.userIds.length || draft.userIds.length > MAX_SELECTED_USERS
    || draft.userIds.some((id) => !Number.isSafeInteger(id) || id <= 0))) {
    errors.push(`Choose between 1 and ${MAX_SELECTED_USERS} valid users.`);
  }
  return errors;
}

export function campaignRequest(draft, idempotencyKey) {
  return {
    idempotencyKey,
    title: draft.title.trim(),
    body: draft.body,
    deepLink: campaignDeepLink(draft.destination, draft.questionId),
    audience: draft.audience,
    userIds: draft.audience === "SELECTED_USERS" ? [...new Set(draft.userIds)] : [],
  };
}

export function formatCampaignCTR(campaign) {
  if (!Number(campaign.acceptedCount)) return "—";
  const ratio = Number(campaign.clickThroughRate);
  return Number.isFinite(ratio) ? `${(ratio * 100).toFixed(1)}%` : "—";
}

export function campaignAudienceLabel(audience) {
  return audience === "SELECTED_USERS" ? "Selected users" : "All registered users";
}
