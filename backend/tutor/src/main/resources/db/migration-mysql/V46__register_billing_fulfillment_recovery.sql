insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'billing-fulfillment-recovery', true, 'FIXED_DELAY', '5s', 3, 300, 300
) on duplicate key update
    schedule_type = values(schedule_type),
    schedule_value = values(schedule_value),
    max_retry_count = values(max_retry_count),
    timeout_seconds = values(timeout_seconds),
    lock_seconds = values(lock_seconds);
