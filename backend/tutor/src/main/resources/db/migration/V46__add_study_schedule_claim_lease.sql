alter table studies
    add column if not exists schedule_claimed_until timestamp with time zone;

create index if not exists idx_studies_due_claim
    on studies (enabled, next_due_at, schedule_claimed_until);
