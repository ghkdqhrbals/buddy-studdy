import { useMemo, useState } from "react";
import { metricCatalog, type MetricDefinition } from "./adminConfig";
import { EmptyState } from "./EmptyState";
import { clamp, fallbackDefinition, formatMetric, formatShortDate } from "./format";
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
  const [hovered, setHovered] = useState<number | null>(null);
  const width = 720;
  const height = 260;
  const padding = { top: 12, right: 18, bottom: 34, left: 42 };
  const allDates = series[0]?.points.map((point) => point.date) ?? [];
  const seriesMax = useMemo(
    () => new Map(series.map((item) => [item.metricKey, Math.max(1, ...item.points.map((point) => point.value))])),
    [series],
  );
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const activeIndex = hovered ?? Math.max(0, allDates.length - 1);
  const x = (index: number) => padding.left + (allDates.length <= 1 ? 0 : (index / (allDates.length - 1)) * plotWidth);
  const y = (metricKey: string, value: number) => {
    const max = seriesMax.get(metricKey) ?? 1;
    return padding.top + (1 - value / max) * plotHeight;
  };

  const linePath = (item: AdminMetricSeries) => {
    return item.points.map((point, index) => {
      return `${index === 0 ? "M" : "L"} ${x(index)} ${y(item.metricKey, point.value)}`;
    }).join(" ");
  };

  const moveHover = (clientX: number, bounds: DOMRect) => {
    const ratio = Math.max(0, Math.min(1, (clientX - bounds.left - padding.left * (bounds.width / width)) / (plotWidth * (bounds.width / width))));
    setHovered(Math.round(ratio * Math.max(0, allDates.length - 1)));
  };

  if (series.length === 0 || allDates.length === 0) {
    return <EmptyState title="No chart data" message="Try a wider date range." compact />;
  }

  return (
    <div className="chart-wrap horizontal-scroll">
      <div className="chart-canvas">
        <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label="Metric trend chart">
          {[0, 0.25, 0.5, 0.75, 1].map((tick) => {
            const yy = padding.top + tick * plotHeight;
            const value = Math.round((1 - tick) * 100);
            return (
              <g key={tick}>
                <line x1={padding.left} x2={width - padding.right} y1={yy} y2={yy} className="grid-line" />
                <text x={padding.left - 10} y={yy + 4} textAnchor="end" className="axis-label">{value}%</text>
              </g>
            );
          })}
          {series.map((item) => {
            const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
            return <path key={item.metricKey} d={linePath(item)} className="line-path" stroke={definition.color} />;
          })}
          {hovered !== null ? series.map((item) => {
            const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
            const active = item.points[activeIndex];
            if (!active) return null;
            return <circle key={item.metricKey} cx={x(activeIndex)} cy={y(item.metricKey, active.value)} r={4} className="chart-dot" stroke={definition.color} />;
          }) : null}
          {hovered !== null ? <line x1={x(activeIndex)} x2={x(activeIndex)} y1={padding.top} y2={height - padding.bottom} className="hover-line" /> : null}
          {xTicks(allDates).map((tick) => (
            <text key={`${tick.index}-${tick.date}`} x={x(tick.index)} y={height - 12} textAnchor={tick.anchor} className="axis-label">
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
        {hovered !== null ? <ChartTooltip index={activeIndex} dates={allDates} series={series} left={clamp((x(activeIndex) / width) * 100, 14, 86)} /> : null}
      </div>
      <div className="chart-legend">
        {series.map((item) => {
          const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
          return (
            <span key={item.metricKey}>
              <i style={{ background: definition.color }} />
              {definition.shortLabel}
            </span>
          );
        })}
      </div>
    </div>
  );
}

function ChartTooltip({ index, dates, series, left }: { index: number; dates: string[]; series: AdminMetricSeries[]; left: number }) {
  const date = dates[index];
  if (!date) return null;
  return (
    <div className="chart-tooltip" style={{ left: `${left}%` }}>
      <strong>{formatShortDate(date)}</strong>
      {series.map((item) => {
        const definition = metricCatalog[item.metricKey] ?? fallbackDefinition(item.metricKey);
        const value = item.points[index]?.value ?? 0;
        const max = Math.max(1, ...item.points.map((point) => point.value));
        const normalized = Math.round((value / max) * 100);
        return (
          <span key={item.metricKey}>
            <i style={{ background: definition.color }} />
            <small>{definition.shortLabel}</small>
            <b>{formatMetric(definition, value)}</b>
            <em>{normalized}%</em>
          </span>
        );
      })}
    </div>
  );
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
