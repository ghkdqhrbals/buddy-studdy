alter table questions
    drop check chk_questions_status;

alter table questions
    modify column status varchar(32) not null
        comment 'Question lifecycle state. Values: ungraded, grading, graded, skipped',
    add column grading_last_event_id bigint null
        comment 'Latest durable question_grading_events.id applied to this question projection'
        after grading_error,
    add constraint chk_questions_status
        check (status in ('ungraded', 'grading', 'graded', 'skipped'));

alter table question_grading_events
    add column question_status varchar(32) not null default 'grading'
        comment 'Question lifecycle projection after this event. Values: grading, graded'
        after status;

update question_grading_events
set question_status = 'graded'
where status = 'COMPLETED';

update questions q
join (
    select question_id, max(id) as last_event_id
    from question_grading_events
    group by question_id
) grading_event on grading_event.question_id = q.id
set q.grading_last_event_id = grading_event.last_event_id;

update questions
set status = 'grading'
where grading_request_id is not null
  and grading_status is not null
  and score is null
  and status = 'ungraded';
