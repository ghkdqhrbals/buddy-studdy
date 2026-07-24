type ChartSeries = {
  label: string;
  values: number[];
  color: string;
  dashed?: boolean;
  showValues?: boolean;
};

type PerformanceChartProps = {
  title: string;
  description: string;
  xValues: number[];
  xLabel: string;
  yLabel: string;
  yTicks: number[];
  series: ChartSeries[];
  scale?: "linear" | "log";
  formatX?: (value: number) => string;
  formatY?: (value: number) => string;
};

const width = 760;
const height = 310;
const plot = { left: 70, right: 736, top: 26, bottom: 254 };

function positionX(index: number, count: number) {
  if (count <= 1) return plot.left;
  return plot.left + (index / (count - 1)) * (plot.right - plot.left);
}

function positionY(value: number, min: number, max: number, scale: "linear" | "log") {
  const safeValue = scale === "log" ? Math.max(value, 1) : value;
  const safeMin = scale === "log" ? Math.log10(Math.max(min, 1)) : min;
  const safeMax = scale === "log" ? Math.log10(Math.max(max, 1)) : max;
  const normalizedValue = scale === "log" ? Math.log10(safeValue) : safeValue;
  const ratio = (normalizedValue - safeMin) / Math.max(safeMax - safeMin, Number.EPSILON);
  return plot.bottom - ratio * (plot.bottom - plot.top);
}

function linePath(
  values: number[],
  min: number,
  max: number,
  scale: "linear" | "log",
) {
  return values
    .map((value, index) => {
      const x = positionX(index, values.length);
      const y = positionY(value, min, max, scale);
      return `${index === 0 ? "M" : "L"} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");
}

export function PerformanceChart({
  title,
  description,
  xValues,
  xLabel,
  yLabel,
  yTicks,
  series,
  scale = "linear",
  formatX = (value) => String(value),
  formatY = (value) => String(value),
}: PerformanceChartProps) {
  const minimum = Math.min(...yTicks);
  const maximum = Math.max(...yTicks);

  return (
    <figure className="performance-chart">
      <figcaption>
        <strong>{title}</strong>
        <span>{description}</span>
      </figcaption>
      <div className="chart-legend" aria-hidden="true">
        {series.map((item) => (
          <span key={item.label}>
            <i style={{ backgroundColor: item.color }} />
            {item.label}
          </span>
        ))}
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`${title}. ${description}`}
        preserveAspectRatio="xMidYMid meet"
      >
        <title>{title}</title>
        <desc>{description}</desc>
        {yTicks.map((tick) => {
          const y = positionY(tick, minimum, maximum, scale);
          return (
            <g key={tick}>
              <line className="chart-grid" x1={plot.left} x2={plot.right} y1={y} y2={y} />
              <text className="chart-tick" x={plot.left - 10} y={y + 4} textAnchor="end">
                {formatY(tick)}
              </text>
            </g>
          );
        })}
        {xValues.map((value, index) => {
          const x = positionX(index, xValues.length);
          return (
            <g key={`${value}-${index}`}>
              <line className="chart-tick-line" x1={x} x2={x} y1={plot.bottom} y2={plot.bottom + 5} />
              <text className="chart-tick" x={x} y={plot.bottom + 22} textAnchor="middle">
                {formatX(value)}
              </text>
            </g>
          );
        })}
        <line className="chart-axis" x1={plot.left} x2={plot.right} y1={plot.bottom} y2={plot.bottom} />
        <text className="chart-axis-label" x={(plot.left + plot.right) / 2} y={height - 8} textAnchor="middle">
          {xLabel}
        </text>
        <text
          className="chart-axis-label"
          x={16}
          y={(plot.top + plot.bottom) / 2}
          textAnchor="middle"
          transform={`rotate(-90 16 ${(plot.top + plot.bottom) / 2})`}
        >
          {yLabel}
        </text>
        {series.map((item) => (
          <g key={item.label}>
            <path
              d={linePath(item.values, minimum, maximum, scale)}
              fill="none"
              stroke={item.color}
              strokeDasharray={item.dashed ? "8 6" : undefined}
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth="3"
            />
            {item.values.map((value, index) => {
              const x = positionX(index, item.values.length);
              const y = positionY(value, minimum, maximum, scale);
              const first = index === 0;
              const last = index === item.values.length - 1;
              return (
                <g key={`${item.label}-${index}`}>
                  <circle
                    cx={x}
                    cy={y}
                    fill="#ffffff"
                    r="3.5"
                    stroke={item.color}
                    strokeWidth="2"
                  />
                  {item.showValues ? (
                    <text
                      className="chart-value-label"
                      x={first ? x + 8 : last ? x - 8 : x}
                      y={y + 18}
                      textAnchor={first ? "start" : last ? "end" : "middle"}
                    >
                      {formatY(value)}
                    </text>
                  ) : null}
                </g>
              );
            })}
          </g>
        ))}
      </svg>
    </figure>
  );
}
