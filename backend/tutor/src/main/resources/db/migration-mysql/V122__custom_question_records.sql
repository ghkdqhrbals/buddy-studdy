-- GRADED is the existing terminal wire lifecycle for backward-compatible record pages.
-- custom_question and absent grading fields distinguish authored Q&A from AI assessment.
alter table questions
    add constraint chk_custom_question_no_grade check (
        source <> 'custom_question' or
        (status = 'graded' and score is null and is_correct is null and grading_status is null
         and grading_request_id is null and is_public = false and answer is not null)
    );

create table question_custom_requests (
    user_id bigint not null comment 'Account owning the custom-question request',
    idempotency_key varchar(120) not null comment 'Client retry identity scoped to the account',
    question_id bigint not null comment 'Saved custom Q&A in the existing questions record store',
    request_hash varchar(64) not null comment 'SHA-256 of normalized topic ID, language, question and answer',
    created_at datetime(6) not null comment 'UTC time this request first saved its record',
    primary key (user_id, idempotency_key),
    constraint fk_custom_request_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_custom_request_question foreign key (question_id) references questions(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='Idempotent creation receipts for private user-authored question and answer records';
