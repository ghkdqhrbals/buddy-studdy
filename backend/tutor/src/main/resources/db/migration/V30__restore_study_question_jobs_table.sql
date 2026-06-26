create table if not exists study_question_jobs (
    id bigserial primary key,
    study_id bigint not null,
    device_id varchar(191) not null,
    user_id bigint not null,
    scheduled_at timestamp with time zone not null,
    status varchar(32) not null,
    attempt_count integer not null default 0,
    locked_at timestamp with time zone,
    locked_by varchar(128),
    completed_at timestamp with time zone,
    canceled_at timestamp with time zone,
    last_error text,
    created_question_id bigint,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_study_question_jobs_due
    on study_question_jobs (status, scheduled_at, id);

create index if not exists idx_study_question_jobs_study_status
    on study_question_jobs (study_id, status, scheduled_at);

create index if not exists idx_study_question_jobs_user
    on study_question_jobs (user_id, updated_at);
