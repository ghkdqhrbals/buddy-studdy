create table question_generation_sagas (
    correlation_id varchar(36) primary key,
    user_id bigint not null,
    study_id bigint not null,
    topic_id bigint not null,
    question_id bigint,
    source varchar(16) not null,
    status varchar(24) not null,
    active_topic_id bigint generated always as (
        case when status in ('QUEUED', 'GENERATING', 'TRANSLATING') then topic_id else null end
    ) stored,
    current_step varchar(24) not null,
    idempotency_key varchar(120) not null,
    quota_period_started_at datetime(6) not null,
    quota_refunded_at datetime(6),
    failed_step varchar(24),
    error_code varchar(80),
    error_message varchar(1000),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    completed_at datetime(6),
    constraint uq_question_generation_saga_idempotency unique (user_id, idempotency_key),
    constraint uq_question_generation_saga_active_topic unique (user_id, active_topic_id),
    constraint uq_question_generation_saga_question unique (question_id),
    constraint chk_question_generation_saga_source check (source in ('MANUAL', 'SCHEDULED')),
    constraint chk_question_generation_saga_status
        check (status in ('QUEUED', 'GENERATING', 'TRANSLATING', 'COMPLETED', 'FAILED')),
    index idx_question_generation_saga_user_updated (user_id, updated_at desc),
    index idx_question_generation_saga_status_updated (status, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table stream_consumer_inbox (
    event_id varchar(120) not null,
    consumer_group varchar(120) not null,
    correlation_id varchar(36) not null,
    status varchar(16) not null,
    claim_token varchar(36),
    attempts int not null default 0,
    lease_expires_at datetime(6),
    last_error varchar(1000),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    completed_at datetime(6),
    primary key (event_id, consumer_group),
    constraint chk_stream_consumer_inbox_status check (status in ('PROCESSING', 'SUCCEEDED')),
    index idx_stream_consumer_inbox_lease (status, lease_expires_at),
    index idx_stream_consumer_inbox_correlation (correlation_id, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
