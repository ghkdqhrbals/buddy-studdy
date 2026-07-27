alter table questions
    add column topic_en varchar(255) null after hint_en;

update questions
set topic_en = topic
where lower(language) like 'en%';

insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'question-topic-translation-backfill', true, 'FIXED_DELAY', '60s', 3, 300, 300
) on duplicate key update
    schedule_type = values(schedule_type),
    schedule_value = values(schedule_value),
    timeout_seconds = values(timeout_seconds),
    lock_seconds = values(lock_seconds);
