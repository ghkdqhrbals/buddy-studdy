alter table if exists studies
    drop column if exists is_question_public;

alter table if exists schedules
    drop column if exists is_question_public;
