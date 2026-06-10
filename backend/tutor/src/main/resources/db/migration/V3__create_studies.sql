alter table users
    add column if not exists app_language varchar(16) not null default 'ko';

create table if not exists studies (
    id bigserial primary key,
    device_id varchar(191) not null,
    user_id bigint not null,
    topic varchar(255) not null,
    difficulty_level integer not null,
    interval_minutes integer not null,
    enabled boolean not null,
    notification_sound varchar(64),
    custom_prompt text not null,
    openai_model varchar(64) not null,
    max_history_count integer not null,
    next_due_at timestamp with time zone,
    last_sent_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_studies_user_topic unique (user_id, topic)
);

create index if not exists idx_studies_due on studies (enabled, next_due_at);
create index if not exists idx_studies_user_updated on studies (user_id, updated_at);
create index if not exists idx_studies_device_user on studies (device_id, user_id);

create table if not exists schedules (
    id bigserial primary key,
    device_id varchar(191) not null,
    user_id bigint,
    topic varchar(255) not null,
    difficulty_level integer not null,
    interval_minutes integer not null,
    enabled boolean not null,
    notification_sound varchar(64),
    custom_prompt text not null,
    app_language varchar(16) not null,
    openai_model varchar(64) not null,
    max_history_count integer not null,
    openai_api_key_cipher text,
    next_due_at timestamp with time zone,
    last_sent_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

insert into studies (
    device_id,
    user_id,
    topic,
    difficulty_level,
    interval_minutes,
    enabled,
    notification_sound,
    custom_prompt,
    openai_model,
    max_history_count,
    next_due_at,
    last_sent_at,
    last_error,
    created_at,
    updated_at
)
select
    s.device_id,
    s.user_id,
    s.topic,
    s.difficulty_level,
    s.interval_minutes,
    s.enabled,
    s.notification_sound,
    s.custom_prompt,
    s.openai_model,
    s.max_history_count,
    s.next_due_at,
    s.last_sent_at,
    s.last_error,
    s.created_at,
    s.updated_at
from schedules s
where s.user_id is not null
  and not exists (
      select 1
      from studies existing
      where existing.user_id = s.user_id
        and lower(existing.topic) = lower(s.topic)
  );

alter table questions
    add column if not exists study_id bigint;

update questions q
set study_id = st.id
from studies st
where q.study_id is null
  and q.user_id = st.user_id
  and lower(q.topic) = lower(st.topic);

create index if not exists idx_questions_study_created on questions (study_id, created_at);
create index if not exists idx_questions_pending_study_v2 on questions (study_id, deleted_at, skipped_at, score, status);
