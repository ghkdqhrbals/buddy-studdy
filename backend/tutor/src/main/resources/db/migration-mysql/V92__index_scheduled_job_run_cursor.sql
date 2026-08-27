alter table scheduled_job_runs
    add index idx_scheduled_job_runs_name_id (job_name, id),
    algorithm=inplace,
    lock=none;
