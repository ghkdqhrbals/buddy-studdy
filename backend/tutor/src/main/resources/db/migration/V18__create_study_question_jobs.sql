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

insert into study_question_jobs (
    study_id,
    device_id,
    user_id,
    scheduled_at,
    status,
    attempt_count,
    created_at,
    updated_at
)
select
    s.id,
    s.device_id,
    s.user_id,
    coalesce(s.next_due_at, s.created_at),
    'SCHEDULED',
    0,
    now(),
    now()
from studies s
where s.enabled = true
  and s.next_due_at is not null
  and not exists (
      select 1
      from study_question_jobs j
      where j.study_id = s.id
        and j.status in ('SCHEDULED', 'PROCESSING')
  );

drop table if exists schedules;
