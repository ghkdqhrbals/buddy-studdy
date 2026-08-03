alter table questions drop constraint if exists chk_questions_status;

alter table questions
    add constraint chk_questions_status
        check (status in ('ungraded', 'grading', 'graded', 'failed', 'skipped'));

update questions
set status = 'failed',
    updated_at = current_timestamp
where grading_status = 'FAILED'
  and status = 'grading';

comment on column questions.status is
    'Question lifecycle state. Values: ungraded, grading, graded, failed, skipped';
