alter table questions
    drop check chk_questions_status;

alter table questions
    modify column status varchar(32) not null
        comment 'Question lifecycle state. Values: ungraded, grading, graded, failed, skipped',
    add constraint chk_questions_status
        check (status in ('ungraded', 'grading', 'graded', 'failed', 'skipped'));

update questions
set status = 'failed',
    updated_at = current_timestamp(6)
where grading_status = 'FAILED'
  and status = 'grading';

alter table question_grading_events
    modify column question_status varchar(32) not null
        comment 'Question lifecycle projection after this event. Values: grading, graded, failed';
