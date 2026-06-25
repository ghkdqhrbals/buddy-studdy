create table if not exists admin_daily_metrics (
    id bigserial primary key,
    metric_date date not null,
    metric_key varchar(80) not null,
    dimension varchar(255) not null default '',
    value double precision not null,
    sample_count bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_admin_daily_metrics_day_key_dimension unique (metric_date, metric_key, dimension)
);

create index if not exists idx_admin_daily_metrics_key_date on admin_daily_metrics (metric_key, metric_date);
create index if not exists idx_admin_daily_metrics_date on admin_daily_metrics (metric_date);
