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
