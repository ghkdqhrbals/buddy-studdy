import { metricCatalog, type MetricDefinition } from "./adminConfig";
import type { MetricKind } from "./types";

export function metricKind(metricKey: string): MetricKind {
  return metricCatalog[metricKey]?.kind ?? "count";
}

export function fallbackDefinition(metricKey: string): MetricDefinition {
  return { key: metricKey, label: metricKey, shortLabel: metricKey, kind: "count", color: "#64748b", description: metricKey };
}

export function formatMetric(definition: MetricDefinition, value: number): string {
  if (definition.kind === "rate") return `${roundOne(value)}%`;
  if (definition.kind === "duration") return `${roundOne(value)}h`;
  if (definition.kind === "days") return `${roundOne(value)}d`;
  return formatCompact(value);
}

export function formatDelta(definition: MetricDefinition, value: number): string {
  if (value === 0) return "0";
  const sign = value > 0 ? "+" : "";
  if (definition.kind === "rate") return `${sign}${roundOne(value)}pp`;
  return `${sign}${formatMetric(definition, value)}`;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export function roundOne(value: number): number {
  return Math.round(value * 10) / 10;
}

export function formatCompact(value: number): string {
  return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: value < 1000 ? 0 : 1 }).format(value);
}

export function statusLabel(status: string): string {
  if (status === "SUCCESS") return "Success";
  if (status === "FAILED") return "Failed";
  if (status === "RUNNING") return "Running";
  return status;
}

export function formatShortDate(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "2-digit", day: "2-digit" }).format(new Date(value));
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("en", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
