create table if not exists redis_event_outbox (
    id bigserial primary key,
    event_id varchar(120) not null,
    event_type varchar(64) not null,
    payload_version integer not null default 1,
    payload_json text not null,
    status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    next_attempt_at timestamp with time zone not null,
    claimed_at timestamp with time zone,
    published_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_redis_event_outbox_type_event unique (event_type, event_id),
    constraint chk_redis_event_outbox_status
        check (status in ('PENDING', 'PROCESSING', 'PUBLISHED'))
);

create index if not exists idx_redis_event_outbox_dispatch
    on redis_event_outbox (status, next_attempt_at, created_at, id);

create index if not exists idx_redis_event_outbox_claim_recovery
    on redis_event_outbox (status, claimed_at)
    where status = 'PROCESSING';
