update users
set allow_public_questions = true,
    display_name = 'Benchmark User',
    updated_at = now(6)
where id = (
    select user_id
    from devices
    where device_id = @device_id
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
with recursive series(id) as (
    select 1
    union all
    select id + 1 from series where id < 100
)
select
    @device_id,
    d.user_id,
    concat('Benchmark Topic ', series.id),
    mod(series.id, 10) + 1,
    60,
    false,
    'default',
    concat('Load-test fixture ', repeat('x', 120)),
    'gpt-5.4',
    1000,
    null,
    null,
    null,
    date_sub(now(6), interval series.id minute),
    date_sub(now(6), interval series.id minute)
from devices d
cross join series
where d.device_id = @device_id;

insert into questions (
    device_id,
    user_id,
    study_id,
    question,
    hint,
    topic,
    language,
    difficulty_level,
    scheduled_for,
    sent_at,
    status,
    answer,
    score,
    is_correct,
    feedback,
    explanation,
    answered_at,
    graded_at,
    source,
    is_public,
    created_at,
    updated_at
)
with recursive series(id) as (
    select 1
    union all
    select id + 1 from series where id < 500
)
select
    @device_id,
    d.user_id,
    s.id,
    concat('Benchmark question ', series.id, ': ', repeat('question ', 20)),
    concat('Benchmark hint ', series.id),
    s.topic,
    'ko',
    mod(series.id, 10) + 1,
    date_sub(now(6), interval series.id minute),
    date_sub(now(6), interval series.id minute),
    'graded',
    concat('Benchmark answer ', repeat('answer ', 20)),
    mod(series.id, 101),
    mod(series.id, 2) = 0,
    concat('Benchmark feedback ', repeat('feedback ', 12)),
    concat('Benchmark explanation ', repeat('explanation ', 20)),
    date_sub(now(6), interval series.id minute),
    date_sub(now(6), interval series.id minute),
    'benchmark',
    true,
    date_sub(now(6), interval series.id minute),
    date_sub(now(6), interval series.id minute)
from devices d
cross join series
join studies s
  on s.user_id = d.user_id
 and s.topic = concat('Benchmark Topic ', mod(series.id - 1, 100) + 1)
where d.device_id = @device_id;

insert into question_stats (question_id, like_count, comment_count, view_count, updated_at)
select id, mod(id, 50), mod(id, 10), mod(id, 500), now(6)
from questions
where source = 'benchmark';

analyze table users, devices, studies, questions, question_stats;
