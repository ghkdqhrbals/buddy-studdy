alter table scheduled_job_runs
    add index idx_scheduled_job_runs_started_id (started_at desc, id desc),
    algorithm=inplace,
    lock=none;
