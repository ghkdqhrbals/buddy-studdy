\set ON_ERROR_STOP on

update users
set allow_public_questions = true,
    display_name = 'Benchmark User',
    updated_at = now()
where id = (
    select user_id
    from devices
    where device_id = :'device_id'
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
    :'device_id',
    d.user_id,
    'Benchmark Topic ' || series.id,
    (series.id % 10) + 1,
    60,
    false,
    'default',
    'Load-test fixture ' || repeat('x', 120),
    'gpt-5.4',
    1000,
    null,
    null,
    null,
    now() - (series.id || ' minutes')::interval,
    now() - (series.id || ' minutes')::interval
from devices d
cross join generate_series(1, 100) as series(id)
where d.device_id = :'device_id';

with benchmark_user as (
    select user_id
    from devices
    where device_id = :'device_id'
), inserted_questions as (
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
    select
        :'device_id',
        u.user_id,
        s.id,
        'Benchmark question ' || series.id || ': ' || repeat('question ', 20),
        'Benchmark hint ' || series.id,
        s.topic,
        'ko',
        (series.id % 10) + 1,
        now() - (series.id || ' minutes')::interval,
        now() - (series.id || ' minutes')::interval,
        'graded',
        'Benchmark answer ' || repeat('answer ', 20),
        (series.id % 101),
        (series.id % 2 = 0),
        'Benchmark feedback ' || repeat('feedback ', 12),
        'Benchmark explanation ' || repeat('explanation ', 20),
        now() - (series.id || ' minutes')::interval,
        now() - (series.id || ' minutes')::interval,
        'benchmark',
        true,
        now() - (series.id || ' minutes')::interval,
        now() - (series.id || ' minutes')::interval
    from benchmark_user u
    cross join generate_series(1, 500) as series(id)
    join studies s
      on s.user_id = u.user_id
     and s.topic = 'Benchmark Topic ' || (((series.id - 1) % 100) + 1)
    returning id, user_id, topic, question, answer, feedback, explanation, score, answered_at, created_at, updated_at
)
insert into question_search (
    question_id,
    user_id,
    topic,
    question,
    answer,
    feedback,
    explanation,
    author_display_name,
    public_question,
    score,
    answered_at,
    deleted_at,
    created_at,
    updated_at,
    language
)
select
    q.id,
    q.user_id,
    q.topic,
    q.question,
    q.answer,
    q.feedback,
    q.explanation,
    'Benchmark User',
    true,
    q.score,
    q.answered_at,
    null,
    q.created_at,
    q.updated_at,
    'ko'
from inserted_questions q;

insert into question_stats (question_id, like_count, comment_count, view_count, updated_at)
select id, id % 50, id % 10, id % 500, now()
from questions
where source = 'benchmark';

analyze users;
analyze devices;
analyze studies;
analyze questions;
analyze question_search;
analyze question_stats;
