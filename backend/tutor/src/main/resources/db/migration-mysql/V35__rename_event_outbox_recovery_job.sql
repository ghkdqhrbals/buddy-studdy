insert ignore into scheduled_jobs (
    job_name,
    enabled,
    schedule_type,
    schedule_value,
    max_retry_count,
    timeout_seconds,
    lock_seconds,
    created_at,
    updated_at
)
select
    'event-outbox-dispatch',
    enabled,
    schedule_type,
    schedule_value,
    max_retry_count,
    timeout_seconds,
    lock_seconds,
    created_at,
    current_timestamp(6)
from scheduled_jobs
where job_name = 'question-push-outbox-dispatch';

update scheduled_job_runs
set job_name = 'event-outbox-dispatch'
where job_name = 'question-push-outbox-dispatch';

delete from scheduled_jobs
where job_name = 'question-push-outbox-dispatch';
