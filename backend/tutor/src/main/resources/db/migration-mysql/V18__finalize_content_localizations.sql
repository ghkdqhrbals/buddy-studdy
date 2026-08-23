insert into question_localizations (
    question_id, target_language, source_language, source_hash,
    topic, question, hint, status, provider, translation_version,
    created_at, updated_at
)
select
    id,
    'en',
    source_language,
    sha2(concat(topic, char(31), question, char(31), coalesce(hint, '')), 256),
    coalesce(topic_en, topic),
    question_en,
    hint_en,
    'READY',
    'legacy-finalization',
    1,
    created_at,
    updated_at
from questions
where translation_status = 'READY'
  and question_en is not null
  and source_language <> 'en'
on duplicate key update
    topic = values(topic),
    question = values(question),
    hint = values(hint),
    source_language = values(source_language),
    source_hash = values(source_hash),
    status = 'READY',
    provider = coalesce(provider, values(provider)),
    error = null,
    updated_at = values(updated_at);

delete from question_localizations where target_language = source_language;
delete from answer_localizations where target_language = source_language;
delete from grading_localizations where target_language = source_language;
delete from question_comment_localizations where target_language = source_language;

create table question_search (
    question_id bigint not null,
    language varchar(16) not null,
    topic text,
    question text,
    answer text,
    feedback text,
    explanation text,
    updated_at datetime(6) not null,
    primary key (question_id, language),
    constraint fk_question_search_question
        foreign key (question_id) references questions(id) on delete cascade,
    index idx_question_search_language_question (language, question_id),
    fulltext index ft_question_search_content (topic, question, answer, feedback, explanation)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

insert into question_search (
    question_id, language, topic, question, answer, feedback, explanation, updated_at
)
select
    q.id,
    languages.language,
    case
        when q.source_language = languages.language then q.topic
        when ql.status = 'READY' then ql.topic
        else null
    end,
    case
        when q.source_language = languages.language then q.question
        when ql.status = 'READY' then ql.question
        else null
    end,
    case
        when q.answer_source_language = languages.language then q.answer
        when al.status = 'READY' then al.answer
        else null
    end,
    case
        when q.ai_response_source_language = languages.language then q.feedback
        when gl.status = 'READY' then gl.feedback
        else null
    end,
    case
        when q.ai_response_source_language = languages.language then q.explanation
        when gl.status = 'READY' then gl.explanation
        else null
    end,
    q.updated_at
from questions q
cross join (
    select 'ko' as language
    union all select 'en'
    union all select 'ja'
) languages
left join question_localizations ql
  on ql.question_id = q.id and ql.target_language = languages.language
left join answer_localizations al
  on al.question_id = q.id and al.target_language = languages.language
left join grading_localizations gl
  on gl.question_id = q.id and gl.target_language = languages.language
where q.source_language = languages.language
   or q.answer_source_language = languages.language
   or q.ai_response_source_language = languages.language
   or ql.status = 'READY'
   or al.status = 'READY'
   or gl.status = 'READY';

drop index idx_questions_english_visibility on questions;

alter table questions
    drop column question_en,
    drop column hint_en,
    drop column topic_en,
    drop column translation_status,
    drop column translation_error,
    drop column language;

alter table questions
    add constraint chk_questions_source_language
        check (source_language in ('ko', 'en', 'ja')),
    add constraint chk_questions_answer_source_language
        check (answer_source_language is null or answer_source_language in ('ko', 'en', 'ja')),
    add constraint chk_questions_ai_response_source_language
        check (ai_response_source_language is null or ai_response_source_language in ('ko', 'en', 'ja'));

alter table question_comments
    modify source_language varchar(16) not null,
    add constraint chk_question_comments_source_language
        check (source_language in ('ko', 'en', 'ja'));
