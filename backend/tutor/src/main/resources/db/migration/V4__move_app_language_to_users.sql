alter table users
    add column if not exists app_language varchar(16) not null default 'ko';

alter table studies
    drop column if exists app_language;
