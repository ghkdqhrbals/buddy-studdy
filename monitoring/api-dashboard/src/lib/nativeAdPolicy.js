export const MINIMUM_POSITIVE_REPEAT_GAP_SECONDS = 60;
export const MAXIMUM_REPEAT_GAP_SECONDS = 2_592_000;

export function isValidRepeatGapSeconds(value) {
  return Number.isInteger(value)
    && value <= MAXIMUM_REPEAT_GAP_SECONDS
    && (value === 0 || value >= MINIMUM_POSITIVE_REPEAT_GAP_SECONDS);
}

export function repeatGapLabel(value) {
  const seconds = Number(value || 0);
  if (seconds === 0) return "No repeat limit";
  if (seconds % 3600 === 0) return `${seconds / 3600}h`;
  if (seconds < 60) return `${seconds}s`;
  if (seconds % 60 === 0) return `${seconds / 60}m`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
