create table if not exists user_memberships (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    tier varchar(32) not null default 'TIER1',
    status varchar(32) not null default 'ACTIVE',
    started_at timestamptz not null default now(),
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_user_memberships_user_status
    on user_memberships(user_id, status);

create table if not exists user_monthly_question_usage (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    year_month varchar(7) not null,
    system_question_count integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_user_monthly_question_usage_user_month unique (user_id, year_month)
);

create index if not exists idx_user_monthly_question_usage_user
    on user_monthly_question_usage(user_id);
