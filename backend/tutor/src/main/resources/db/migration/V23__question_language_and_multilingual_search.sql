alter table questions
    add column if not exists language varchar(16) not null default 'ko';

update questions q
set language = coalesce(u.app_language, 'ko')
from users u
where q.user_id = u.id
  and (q.language is null or q.language = '');

alter table question_search
    add column if not exists language varchar(16) not null default 'ko';

update question_search qs
set language = coalesce(q.language, u.app_language, 'ko')
from questions q
left join users u on u.id = q.user_id
where qs.question_id = q.id
  and (qs.language is null or qs.language = '');

alter table question_search
    drop constraint if exists question_search_pkey;

alter table question_search
    add constraint question_search_pkey primary key (question_id, language);

create index if not exists idx_question_search_public_language_created
    on question_search (public_question, language, score, deleted_at, created_at);
