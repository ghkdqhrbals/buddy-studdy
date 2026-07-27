alter table questions
    add column grading_request_id varchar(36) null after grading_model,
    add column grading_status varchar(40) null after grading_request_id,
    add column grading_error varchar(255) null after grading_status,
    add column grading_requested_at datetime(6) null after grading_error,
    add column grading_started_at datetime(6) null after grading_requested_at;

create index idx_questions_grading_request
    on questions (grading_request_id);

create table question_grading_events (
    id bigint not null auto_increment,
    question_id bigint not null,
    user_id bigint not null,
    request_id varchar(36) not null,
    status varchar(40) not null,
    error_message varchar(255) null,
    created_at datetime(6) not null,
    primary key (id),
    unique key uk_question_grading_event_request_status (request_id, status),
    key idx_question_grading_events_owner_cursor (question_id, user_id, id),
    constraint fk_question_grading_events_question
        foreign key (question_id) references questions (id) on delete cascade
);
