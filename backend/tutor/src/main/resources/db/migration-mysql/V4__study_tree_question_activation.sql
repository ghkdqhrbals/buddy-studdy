alter table studies
    add column active_for_questions boolean not null default false after enabled;

update studies
set active_for_questions = true
where parent_study_id is null;

create index idx_studies_user_question_active
    on studies (user_id, active_for_questions, parent_study_id, sort_order, id);
