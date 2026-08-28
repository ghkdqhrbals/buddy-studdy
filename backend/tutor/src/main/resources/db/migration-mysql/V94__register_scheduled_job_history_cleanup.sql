insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'scheduled-job-history-cleanup', true, 'CRON', '0 40 3 * * * UTC', 3, 900, 900
) on duplicate key update
    schedule_type = values(schedule_type),
    schedule_value = values(schedule_value),
    max_retry_count = values(max_retry_count),
    timeout_seconds = values(timeout_seconds),
    lock_seconds = values(lock_seconds);
