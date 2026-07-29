insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'answer-grading-watchdog', true, 'FIXED_DELAY', '30s', 3, 300, 300
) on duplicate key update
    schedule_type = values(schedule_type),
    schedule_value = values(schedule_value),
    timeout_seconds = values(timeout_seconds),
    lock_seconds = values(lock_seconds);

update scheduled_jobs
set schedule_type = 'FIXED_DELAY',
    schedule_value = '1s'
where job_name = 'question-push-outbox-dispatch';

delete from scheduled_jobs
where job_name = 'question-topic-translation-backfill';
