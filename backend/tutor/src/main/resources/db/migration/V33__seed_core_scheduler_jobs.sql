insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value)
values
    ('question-schedule', true, 'FIXED_DELAY', 'buddystudy.scheduler.poll-ms'),
    ('question-push-outbox-dispatch', true, 'FIXED_DELAY', 'buddystudy.scheduler.poll-ms')
on conflict (job_name) do nothing;
