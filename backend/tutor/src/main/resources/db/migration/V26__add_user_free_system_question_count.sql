alter table users
    add column if not exists free_system_question_count integer not null default 0;
