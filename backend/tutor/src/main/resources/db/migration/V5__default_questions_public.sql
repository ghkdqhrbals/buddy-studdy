alter table studies
    alter column is_question_public set default true;

alter table schedules
    alter column is_question_public set default true;
