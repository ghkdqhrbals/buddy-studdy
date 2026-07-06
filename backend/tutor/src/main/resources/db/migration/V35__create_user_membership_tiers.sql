create table if not exists user_membership_tiers (
    tier_code varchar(32) primary key,
    monthly_question_limit integer not null,
    description varchar(255) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

insert into user_membership_tiers (
    tier_code,
    monthly_question_limit,
    description
)
values
    ('TIER0', 0, 'User-provided API key tier. Disabled while system-key-only generation is active.'),
    ('TIER1', 30, 'Free monthly question quota.'),
    ('TIER2', 1000, 'Extended monthly question quota.'),
    ('TIER3', 3000, 'Maximum monthly question quota.')
on conflict (tier_code) do update
set monthly_question_limit = excluded.monthly_question_limit,
    description = excluded.description,
    updated_at = now();
