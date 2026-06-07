# BuddyStuddy Loki Search Dashboard

This dashboard contains a Kibana-style log timeline plus one log search panel: **Log Timeline** and **Search Results**.

## Import

Import `docs/observability/grafana-loki-search-dashboard.json` in Grafana, then select the Loki data source from the dashboard variables.

API performance and latency metrics are intentionally kept in a separate dashboard:

- `docs/observability/grafana-api-latency-dashboard.json`
- Dashboard UID: `buddystuddy-api-latency`
- Dashboard title: `BuddyStuddy API Performance`

The performance dashboard combines Datadog/Sentry-style traffic summaries with grouped latency tables:

- Request summary: average requests per second in the selected range.
- Traffic trends: `API Calls by Route`, `Responses by Status`, and `Error Responses by Route`.
- Latency trend: `p99 Trend by Route`.
- Endpoint comparison tables: `p50 API Latency`, `p95 API Latency`, and `p99 API Latency`.

The p50, p95, and p99 panels remain separate table panels. Each percentile panel groups endpoints for that percentile together and calculates values over the selected dashboard time range.

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

## API Performance Dashboard

The `BuddyStuddy API Performance` dashboard calculates traffic, error, and latency metrics from backend `api_response` logs. It uses the `route` field emitted by the backend response logger, not the raw request `path`, so path variables aggregate correctly:

```text
path=/api/v1/me/records/71
route=/api/v1/me/records/{record_id}
```

API call frequency is shown as a route-level time series:

```logql
sum by (method, route) (
  count_over_time(
    <selected log query>
      |= `api_response`
      | regexp `"method":"(?P<method>[^"]+)","path":"(?P<path>[^"]+)","route":"(?P<route>[^"]+)","status":(?P<status>[0-9]+),"durationMs":(?P<durationMs>[0-9.]+)`
      | status=~".+" [$__interval]
  )
) / (${__interval_ms} / 1000)
```

Errors are derived from HTTP status codes in the response log:

```logql
status=~"[45].."
```

Each percentile is still a separate table panel:

- `p50 API Latency`
- `p95 API Latency`
- `p99 API Latency`

The query shape is:

```logql
quantile_over_time(
  0.95,
  <selected log query>
    |= `api_response`
    | regexp `"method":"(?P<method>[^"]+)","path":"(?P<path>[^"]+)","route":"(?P<route>[^"]+)","status":(?P<status>[0-9]+),"durationMs":(?P<durationMs>[0-9.]+)`
    | unwrap durationMs
    | __error__="" [$__range]
) by (method, route)
```

This is an instant/stat query over the selected time range. Do not render these percentiles as a time series unless the goal is trend analysis; operational endpoint comparison is easier to read as grouped percentile tables.

## Elasticsearch-Style Log Browsing

The `Search Results` panel uses Grafana's `Logs` visualization. It is intentionally fixed-height, so the dashboard layout stays stable while the log list scrolls inside the panel.

The list shows time, level, and the log message first. Loki labels are hidden from the list to avoid excessive horizontal scrolling. Grafana log syntax highlighting is enabled, so terms from `LogQL Search` line filters are highlighted in the displayed log line.

Do not add display-only `regexp` field extraction or truncation inside the `Search Results` LogQL. Grafana's Loki logs panel requests `categorize-labels`; named regexp captures, `printf`, or `trunc` can put long or split UTF-8 content into extracted fields and make `/api/ds/query` fail with `invalid UTF-8 rune`. Keep the query as the raw log stream and handle row-height limiting in Grafana display behavior or a narrowly targeted frontend override.

If row truncation is needed, clamp only the collapsed list row and never the inline detail body. Validate with Korean text and UTC offset timestamps before deploying, because byte-oriented truncation can split UTF-8 and make Hangul render incorrectly.

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
