insert into user_stats_dirty_keys (
    user_id,
    stat_date,
    topic_key,
    difficulty_level,
    created_at,
    updated_at
)
select distinct
    q.user_id,
    ((coalesce(q.answered_at, q.created_at) at time zone 'UTC')::date) as stat_date,
    regexp_replace(lower(trim(q.topic)), '\s+', ' ', 'g') as topic_key,
    q.difficulty_level,
    now(),
    now()
from questions q
where q.user_id is not null
  and q.deleted_at is null
  and q.score is not null
on conflict (user_id, stat_date, topic_key, difficulty_level)
do update set updated_at = excluded.updated_at;
