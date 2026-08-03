delete from scheduled_jobs
where job_name in ('admin-analytics-recent', 'admin-analytics-correction');
