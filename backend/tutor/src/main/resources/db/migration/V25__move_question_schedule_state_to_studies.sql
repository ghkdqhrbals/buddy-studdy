update studies s
set next_due_at = active_job.scheduled_at,
    last_error = coalesce(active_job.last_error, s.last_error),
    updated_at = now()
from (
    select distinct on (study_id)
        study_id,
        scheduled_at,
        last_error
    from study_question_jobs
    where status in ('SCHEDULED', 'PROCESSING')
    order by study_id, scheduled_at asc, id desc
) active_job
where s.id = active_job.study_id
  and (s.next_due_at is null or s.next_due_at <> active_job.scheduled_at);
