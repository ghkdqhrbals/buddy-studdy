create table if not exists user_stats_dirty_keys (
    id bigserial primary key,
    user_id bigint not null,
    stat_date date not null,
    topic_key varchar(255) not null,
    difficulty_level integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_user_stats_dirty_key unique (user_id, stat_date, topic_key, difficulty_level)
);

create index if not exists idx_user_stats_dirty_updated
    on user_stats_dirty_keys (updated_at, id);

create index if not exists idx_questions_stats_refresh
    on questions (user_id, difficulty_level, answered_at, created_at);
