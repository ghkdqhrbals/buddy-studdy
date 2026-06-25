import { useState } from "react";
import { metricCatalog, type MetricDefinition } from "./adminConfig";
import { EmptyState } from "./EmptyState";
import { clamp, fallbackDefinition, formatCompact, formatMetric, formatShortDate, roundOne } from "./format";
import type { AdminMetricSeries } from "./types";

export function Sparkline({ item, definition }: { item: AdminMetricSeries; definition: MetricDefinition }) {
  const points = item.points;
  const width = 148;
  const height = 34;
  const max = Math.max(1, ...points.map((point) => point.value));
  const min = Math.min(0, ...points.map((point) => point.value));
  const path = points.map((point, index) => {
    const x = points.length <= 1 ? 0 : (index / (points.length - 1)) * width;
    const y = height - ((point.value - min) / Math.max(1, max - min)) * height;
    return `${index === 0 ? "M" : "L"} ${x} ${y}`;
  }).join(" ");
  return (
    <svg className="sparkline" viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <path d={path} stroke={definition.color} />
    </svg>
  );
}

export function MultiLineChart({ series }: { series: AdminMetricSeries[] }) {
  if (series.length === 0 || series.every((item) => item.points.length === 0)) {
    return <EmptyState title="No chart data" message="Try a wider date range." compact />;
  }

  return (
    <div className="trend-grid">
      {series.map((item) => (
        <MetricTrendChart key={item.metricKey} item={item} />
      ))}
    </div>
  );
}

function MetricTrendChart({ item }: { item: AdminMetricSeries }) {
  const [hovered, setHovered] = useState<number | null>(null);
  const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
  const points = item.points;
  const width = 420;
  const height = 132;
  const padding = { top: 12, right: 18, bottom: 22, left: 42 };
  const values = points.map((point) => point.value);
  const rawMax = Math.max(1, ...values);
  const rawMin = Math.min(0, ...values);
  const scale = chartScale(definition, rawMin, rawMax);
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const activeIndex = hovered ?? Math.max(0, points.length - 1);
  const x = (index: number) => padding.left + (points.length <= 1 ? 0 : (index / (points.length - 1)) * plotWidth);
  const y = (value: number) => padding.top + (1 - ((value - scale.min) / Math.max(1, scale.max - scale.min))) * plotHeight;
  const path = points.map((point, index) => `${index === 0 ? "M" : "L"} ${x(index)} ${y(point.value)}`).join(" ");
  const active = points[activeIndex];

  const moveHover = (clientX: number, bounds: DOMRect) => {
    const ratio = clamp((clientX - bounds.left - padding.left * (bounds.width / width)) / (plotWidth * (bounds.width / width)), 0, 1);
    setHovered(Math.round(ratio * Math.max(0, points.length - 1)));
  };

  return (
    <article className="trend-card">
      <div className="trend-card-head">
        <span>
          <i style={{ background: definition.color }} />
          {definition.shortLabel}
        </span>
        <strong>{formatMetric(definition, points.at(-1)?.value ?? 0)}</strong>
      </div>
      <div className="trend-canvas">
        <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label={`${definition.label} trend chart`}>
          {scale.ticks.map((tick) => {
            const yy = y(tick);
            return (
              <g key={tick}>
                <line x1={padding.left} x2={width - padding.right} y1={yy} y2={yy} className="grid-line" />
                <text x={padding.left - 10} y={yy + 4} textAnchor="end" className="axis-label">{formatAxisTick(definition, tick)}</text>
              </g>
            );
          })}
          <line x1={padding.left} x2={width - padding.right} y1={height - padding.bottom} y2={height - padding.bottom} className="axis-line" />
          <path d={path} className="line-path" stroke={definition.color} />
          {active ? (
            <>
              <line x1={x(activeIndex)} x2={x(activeIndex)} y1={padding.top} y2={height - padding.bottom} className="hover-line" />
              <circle cx={x(activeIndex)} cy={y(active.value)} r={3.8} className="chart-dot" stroke={definition.color} />
            </>
          ) : null}
          {xTicks(points.map((point) => point.date)).map((tick) => (
            <text key={`${tick.index}-${tick.date}`} x={x(tick.index)} y={height - 10} textAnchor={tick.anchor} className="axis-label">
              {formatShortDate(tick.date)}
            </text>
          ))}
          <rect
            x={padding.left}
            y={padding.top}
            width={plotWidth}
            height={plotHeight}
            fill="transparent"
            onMouseMove={(event) => moveHover(event.clientX, event.currentTarget.getBoundingClientRect())}
            onMouseLeave={() => setHovered(null)}
          />
        </svg>
        {hovered !== null && active ? (
          <div className="chart-tooltip mini-tooltip" style={{ left: `${clamp((x(activeIndex) / width) * 100, 22, 78)}%` }}>
            <strong>{formatShortDate(active.date)}</strong>
            <span>
              <i style={{ background: definition.color }} />
              <small>{definition.shortLabel}</small>
              <b>{formatMetric(definition, active.value)}</b>
            </span>
          </div>
        ) : null}
      </div>
    </article>
  );
}

function chartScale(definition: MetricDefinition, rawMin: number, rawMax: number): { min: number; max: number; ticks: number[] } {
  if (definition.kind === "rate") {
    const max = Math.max(100, niceCeil(rawMax));
    return { min: 0, max, ticks: [max, max / 2, 0] };
  }
  if (definition.kind === "count" || definition.kind === "days") {
    const max = Math.max(1, niceCeil(rawMax));
    const middle = Math.round(max / 2);
    const ticks = max <= 2 ? [max, 0] : [max, middle, 0];
    return { min: 0, max, ticks: uniqueTicks(ticks) };
  }
  const max = Math.max(1, niceCeil(rawMax));
  const min = Math.max(0, rawMin);
  return { min, max, ticks: uniqueTicks([max, (max + min) / 2, min]) };
}

function uniqueTicks(values: number[]): number[] {
  return values.filter((value, index, self) => self.findIndex((item) => roundOne(item) === roundOne(value)) === index);
}

function niceCeil(value: number): number {
  if (value <= 1) return 1;
  const exponent = Math.floor(Math.log10(value));
  const magnitude = 10 ** exponent;
  const normalized = value / magnitude;
  const nice = normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return nice * magnitude;
}

function formatAxisTick(definition: MetricDefinition, value: number): string {
  if (definition.kind === "rate") return `${roundOne(value)}%`;
  if (definition.kind === "duration") return `${roundOne(value)}h`;
  if (definition.kind === "days") return `${roundOne(value)}d`;
  if (Number.isInteger(value)) return formatCompact(value);
  if (Math.abs(value) < 10) return String(roundOne(value));
  return formatCompact(value);
}

function xTicks(dates: string[]) {
  if (dates.length === 0) return [];
  if (dates.length === 1) return [{ index: 0, date: dates[0], anchor: "middle" as const }];
  const middle = Math.floor((dates.length - 1) / 2);
  return [
    { index: 0, date: dates[0], anchor: "start" as const },
    { index: middle, date: dates[middle], anchor: "middle" as const },
    { index: dates.length - 1, date: dates[dates.length - 1], anchor: "end" as const },
  ];
}
