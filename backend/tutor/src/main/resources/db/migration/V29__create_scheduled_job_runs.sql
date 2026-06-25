create table if not exists scheduled_jobs (
    job_name varchar(120) primary key,
    enabled boolean not null default true,
    schedule_type varchar(40) not null,
    schedule_value varchar(120) not null,
    max_retry_count integer not null default 3,
    timeout_seconds integer not null default 300,
    lock_seconds integer not null default 300,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists scheduled_job_runs (
    id bigserial primary key,
    job_name varchar(120) not null,
    trigger_type varchar(40) not null,
    status varchar(40) not null,
    started_at timestamptz not null,
    finished_at timestamptz null,
    duration_ms bigint null,
    summary varchar(500) null,
    error_message varchar(1000) null,
    retry_of_run_id bigint null references scheduled_job_runs(id),
    created_by varchar(120) not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_scheduled_job_runs_name_started on scheduled_job_runs (job_name, started_at desc);
create index if not exists idx_scheduled_job_runs_status_started on scheduled_job_runs (status, started_at desc);

insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value)
values
    ('admin-analytics-recent', true, 'CRON', '0 */5 * * * *'),
    ('admin-analytics-correction', true, 'CRON', '0 20 3 * * *'),
    ('user-stats-refresh', true, 'CRON', '0 */5 * * * *')
on conflict (job_name) do nothing;
