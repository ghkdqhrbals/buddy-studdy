alter table scheduled_job_runs
    add index idx_scheduled_job_runs_name_started_id (job_name, started_at desc, id desc),
    add index idx_scheduled_job_runs_name_status_started_id (job_name, status, started_at desc, id desc),
    algorithm=inplace,
    lock=none;
