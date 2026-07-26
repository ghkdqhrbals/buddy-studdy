const JSON_START = new Set(["{", "["]);

export function parseNestedValue(value, depth = 0) {
  if (depth > 6) return value;
  if (Array.isArray(value)) return value.map((item) => parseNestedValue(item, depth + 1));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, parseNestedValue(item, depth + 1)]),
    );
  }
  if (typeof value !== "string") return value;
  const trimmed = value.trim();
  if (!JSON_START.has(trimmed[0])) return value;
  try {
    return parseNestedValue(JSON.parse(trimmed), depth + 1);
  } catch {
    return value;
  }
}

export function displayValue(value) {
  if (value === null) return "null";
  if (value === undefined || value === "") return "-";
  if (typeof value === "boolean") return String(value);
  return String(value);
}
