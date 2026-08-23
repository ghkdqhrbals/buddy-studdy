# Admin Analytics On-Demand Aggregation

## Context

Admin analytics stores derived rows in the primary BuddyStudy MySQL database by default.
This reuses the managed R2DBC pool and avoids a second database becoming an analytics-only failure point.
An independent MySQL analytics database remains available only when
`ADMIN_ANALYTICS_DATABASE_URL` is explicitly configured and provisioned.

## Goals

- Keep user-facing APIs independent from admin analytics writes.
- Recalculate analytics only when an authenticated administrator requests it.
- Allow admin charts to read precomputed rows instead of scanning source tables on every request.

## Non-Goals

- Real-time admin analytics.
- Event-sourced metric accuracy.
- Automatic periodic aggregation.
- Aggregation during ordinary chart reads.

## Data Flow

```mermaid
flowchart LR
    Operator["Authenticated admin refresh"] --> Source["Primary DB\nusers, questions, notifications, usage"]
    Source --> Agg["Primary DB\nadmin_daily_metrics"]
    Agg --> Dashboard["/admin dashboard"]
```

## Refresh

- No periodic admin analytics job is registered.
- `POST /api/v1/admin/analytics/refresh` recomputes the selected date range.
- Ordinary metrics reads return the last explicitly refreshed values without
  scanning source tables.

All writes are idempotent upserts keyed by `(metric_date, metric_key, dimension)`.
If a refresh is interrupted, an administrator can safely request the same range again.

## Batch Jobs

Admin analytics does not register a `ManagedJob`, write `scheduled_job_runs`, or
participate in scheduler readiness. Flyway removes the former
`admin-analytics-recent` and `admin-analytics-correction` registry rows while
retaining their historical run records.

## Metric Storage

`admin_daily_metrics` stores:

- `metric_date`
- `metric_key`
- `dimension`
- `value`
- `sample_count`
- `updated_at`

Rate metrics store the computed rate in `value` and the denominator in `sample_count`.

## Operational Notes

- `admin_daily_metrics` is managed by Flyway with the rest of the primary schema.
- The derived rows can be rebuilt from primary source tables.
- Do not set `ADMIN_ANALYTICS_DATABASE_URL` unless the separate MySQL database,
  credentials, schema migrations, and connection capacity are managed independently.
- Admin analytics failures should not block user-facing app behavior.
- Use the authenticated refresh API when the stored range needs to be rebuilt.
