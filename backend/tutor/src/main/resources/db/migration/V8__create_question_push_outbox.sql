create table if not exists question_push_outbox (
    id bigserial primary key,
    record_id bigint not null,
    device_id varchar(191) not null,
    user_id bigint,
    question text not null,
    expected_answer_hint text,
    topic varchar(255) not null,
    difficulty_level integer not null,
    language varchar(16) not null,
    sound varchar(64),
    interval_minutes integer not null,
    status varchar(32) not null,
    attempts integer not null,
    next_attempt_at timestamp with time zone not null,
    published_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_question_push_outbox_pending
    on question_push_outbox (status, next_attempt_at, created_at);

create index if not exists idx_question_push_outbox_record
    on question_push_outbox (record_id);
