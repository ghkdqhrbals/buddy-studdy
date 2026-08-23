alter table questions drop constraint if exists chk_questions_status;

alter table questions
    add constraint chk_questions_status
        check (status in ('ungraded', 'grading', 'graded', 'skipped'));

comment on column questions.status is
    'Question lifecycle state. Values: ungraded, grading, graded, skipped';
