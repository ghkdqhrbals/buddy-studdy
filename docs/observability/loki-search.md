# BuddyStuddy Loki Search Dashboard

This dashboard contains a Kibana-style log timeline, API latency percentile graph, and one log search panel: **Log Timeline**, **API Latency Percentiles**, and **Search Results**.

## Import

Import `docs/observability/grafana-loki-search-dashboard.json` in Grafana, then select the Loki data source from the dashboard variables.

To apply collapsed-row truncation, inject `docs/observability/grafana-log-row-truncation.css` into the deployed Grafana frontend. Grafana's dashboard JSON does not provide a native "clamp log rows to N lines" option for the Logs panel.

## Query Controls

- `Label selector`: raw LogQL stream selector, for example `{job="buddystuddy-backend"}` or `{container="buddystuddy-nginx"}`. Loki labels are the indexed fields, so narrow this first.
- `Label filters`: optional Grafana ad hoc label filters applied to the Loki data source. New logs include a `level` label so level filtering can be done here instead of inside the log panel.
- `LogQL Search`: optional raw LogQL pipeline inserted after the label selector. Leave it empty to show every line in the selected label/time range.

Examples:

```logql
|= `OpenAI API key validation failed`
|~ `error|failed|timeout`
|~ `error` |~ `deviceId`
!= `health`
```

Use `|~` for regex, `|=` for exact contains, `!=` for excluding text, and chain filters for AND behavior.

Results are newest-first.

## Log Timeline

The `Log Timeline` panel uses the same `Label Selector`, `Label Filters`, and `LogQL Search` constraints as the log list. It renders log counts over time with:

```logql
sum(count_over_time(<selected log query>[$timelineBucket]))
```

The timeline query must stay aligned with `Search Results`: `${labelSelector:raw} ${logqlSearch:raw}`. Do not add display-only regexp extraction here, or the graph can disagree with the log list.

The timeline query uses a hidden Grafana interval variable, `timelineBucket`, with `auto_count=60` and `auto_min=10s`. This keeps the graph aggregated into roughly 60 time buckets for the selected range instead of rendering one-second spikes across the whole panel.

The time series uses full-width bars (`barWidthFactor=1`) so each bucket visually fills its interval instead of rendering as a thin spike.

## API Latency Percentiles

The `API Latency Percentiles` panel calculates p50, p95, and p99 response latency by `method` and `path` from backend `api_response` logs.

The backend emits response logs as prefixed JSON:

```text
api_response {"method":"GET","path":"/api/v1/...","status":200,"durationMs":12.34,...}
```

Because the log line is not pure JSON, the percentile panel intentionally uses `regexp` extraction instead of a raw `| json` parser:

```logql
quantile_over_time(
  0.95,
  <selected log query>
    |= `api_response`
    | regexp `"method":"(?P<method>[^"]+)","path":"(?P<path>[^"]+)","status":(?P<status>[0-9]+),"durationMs":(?P<durationMs>[0-9.]+)`
    | unwrap durationMs
    | __error__="" [$timelineBucket]
) by (method, path)
```

This panel follows the same `Label Selector`, `Label Filters`, and `LogQL Search` controls. Keep `LogQL Search` to line filters (`|=`, `!=`, `|~`, `!~`) when using latency percentiles; parser or formatting stages in the search box can conflict with the percentile extraction stages.

## Elasticsearch-Style Log Browsing

The `Search Results` panel uses Grafana's `Logs` visualization. It is intentionally fixed-height, so the dashboard layout stays stable while the log list scrolls inside the panel.

The list shows time, level, and the log message first. Loki labels are hidden from the list to avoid excessive horizontal scrolling. Grafana log syntax highlighting is enabled, so terms from `LogQL Search` line filters are highlighted in the displayed log line.

Do not add display-only `regexp` field extraction or truncation inside the `Search Results` LogQL. Grafana's Loki logs panel requests `categorize-labels`; named regexp captures, `printf`, or `trunc` can put long or split UTF-8 content into extracted fields and make `/api/ds/query` fail with `invalid UTF-8 rune`. Keep the query as the raw log stream and handle row-height limiting through `grafana-log-row-truncation.css`.

`grafana-log-row-truncation.css` clamps only the collapsed list row to 3 wrapped lines with CSS; it never truncates the log bytes. The inline detail body is excluded from the clamp, and the Grafana detail containers are forced to `overflow: visible` so clicking a row shows the full original log without a nested scrollbar. Validate with Korean text and UTC offset timestamps before deploying any selector changes.

Click a log row to open the log details inline under the row. The deployed Grafana override removes the default nested scroll areas from inline details so the selected log can expand in the page flow.

For the closest Elasticsearch Discover-style workflow:

1. Filter by indexed Loki labels first.
2. Scan the fixed-height log list.
3. Click one row.
4. Review labels/detected fields, then the raw log detail.

## Pagination Model

The panel uses Grafana Logs infinite scrolling over a bounded Loki result set. Loki `maxLines` is fixed at 100 so Grafana does not fetch an unbounded number of rows or freeze Chrome on long log lines.

Loki itself does not expose offset/page-number pagination. It supports cursor-style paging by repeating `/loki/api/v1/query_range` with:

- `limit`
- `direction`
- `start`
- `end`

For example, when reading newest-first, request `direction=backward&limit=500`, then use the oldest timestamp returned as the next request's `end` cursor. Subtract 1ns from the cursor to avoid returning the same final row again. This is the server-side pattern to use if we later build a dedicated log browser outside Grafana.

The practical Grafana workflow for large searches is:

1. Narrow labels first.
2. Keep the dashboard time range bounded.
3. Keep the dashboard time range narrow enough for the incident you are inspecting.
4. Scroll inside the fixed-height `Search Results` panel for additional batches.
5. Move the time range backward/forward when you need a different server-side window.
