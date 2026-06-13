create table if not exists user_stats (
    id bigserial primary key,
    user_id bigint not null,
    stat_date date not null,
    topic_key varchar(255) not null,
    topic varchar(255) not null,
    difficulty_level integer not null,
    response_count integer not null,
    score_count integer not null,
    score_sum integer not null,
    best_score integer not null,
    correct_count integer not null,
    latest_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_user_stats_day_topic_level unique (user_id, stat_date, topic_key, difficulty_level)
);

create index if not exists idx_user_stats_user_date on user_stats (user_id, stat_date);
create index if not exists idx_user_stats_user_topic on user_stats (user_id, topic_key);
