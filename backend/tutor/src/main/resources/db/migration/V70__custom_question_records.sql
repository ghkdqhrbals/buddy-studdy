alter table questions add constraint chk_custom_question_no_grade check (
    source <> 'custom_question' or
    (status = 'graded' and score is null and is_correct is null and grading_status is null
     and grading_request_id is null and is_public = false and answer is not null)
);

create table question_custom_requests (
    user_id bigint not null,
    idempotency_key varchar(120) not null,
    question_id bigint not null,
    request_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    primary key (user_id, idempotency_key),
    foreign key (user_id) references users(id) on delete cascade,
    foreign key (question_id) references questions(id) on delete cascade
);
