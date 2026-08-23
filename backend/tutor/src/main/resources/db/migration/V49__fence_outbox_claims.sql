alter table redis_event_outbox
    add column if not exists claim_token varchar(64);

alter table question_push_outbox
    add column if not exists claimed_at timestamp with time zone;

alter table question_push_outbox
    add column if not exists claim_token varchar(64);

create index if not exists idx_question_push_outbox_claim_recovery
    on question_push_outbox (status, claimed_at);
