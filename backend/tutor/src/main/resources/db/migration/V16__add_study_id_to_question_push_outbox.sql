alter table question_push_outbox
    add column if not exists study_id bigint;

create index if not exists idx_question_push_outbox_study
    on question_push_outbox(study_id);
