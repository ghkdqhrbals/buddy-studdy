alter table questions
    add column question_en text null after hint,
    add column hint_en text null after question_en,
    add column translation_status varchar(32) not null default 'PENDING' after hint_en,
    add column translation_error text null after translation_status;

update questions
set question_en = question,
    hint_en = hint,
    translation_status = 'READY'
where lower(language) like 'en%';

create index idx_questions_english_visibility
    on questions (translation_status, is_public, deleted_at, score, created_at, id);
