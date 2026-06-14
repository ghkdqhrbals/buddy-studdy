alter table questions
    alter column is_public set default true;

alter table question_search
    alter column public_question set default true;
