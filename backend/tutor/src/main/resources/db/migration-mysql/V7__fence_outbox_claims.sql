alter table redis_event_outbox
    add column claim_token varchar(64);

alter table question_push_outbox
    add column claimed_at datetime(6),
    add column claim_token varchar(64),
    add index idx_question_push_outbox_claim_recovery (status, claimed_at);
