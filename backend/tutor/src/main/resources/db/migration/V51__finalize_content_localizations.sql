insert into question_localizations (
    question_id, target_language, source_language, source_hash,
    topic, question, hint, status, provider, translation_version,
    created_at, updated_at
)
select
    id,
    'en',
    source_language,
    encode(sha256(convert_to(topic || chr(31) || question || chr(31) || coalesce(hint, ''), 'UTF8')), 'hex'),
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
on conflict (question_id, target_language) do update set
    topic = excluded.topic,
    question = excluded.question,
    hint = excluded.hint,
    source_language = excluded.source_language,
    source_hash = excluded.source_hash,
    status = 'READY',
    provider = coalesce(question_localizations.provider, excluded.provider),
    error = null,
    updated_at = excluded.updated_at;

delete from question_localizations where target_language = source_language;
delete from answer_localizations where target_language = source_language;
delete from grading_localizations where target_language = source_language;
delete from question_comment_localizations where target_language = source_language;

drop table if exists question_search;

create table question_search (
    question_id bigint not null references questions(id) on delete cascade,
    language varchar(16) not null,
    topic text,
    question text,
    answer text,
    feedback text,
    explanation text,
    updated_at timestamp not null,
    search_vector tsvector generated always as (
        setweight(to_tsvector('simple', coalesce(topic, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(question, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(answer, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(feedback, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(explanation, '')), 'C')
    ) stored,
    primary key (question_id, language)
);

create index idx_question_search_language_question
    on question_search (language, question_id);
create index idx_question_search_vector
    on question_search using gin (search_vector);

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
cross join (values ('ko'), ('en'), ('ja')) languages(language)
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

alter table questions
    drop column if exists question_en,
    drop column if exists hint_en,
    drop column if exists topic_en,
    drop column if exists translation_status,
    drop column if exists translation_error,
    drop column if exists language;

alter table questions
    add constraint chk_questions_source_language
        check (source_language in ('ko', 'en', 'ja')),
    add constraint chk_questions_answer_source_language
        check (answer_source_language is null or answer_source_language in ('ko', 'en', 'ja')),
    add constraint chk_questions_ai_response_source_language
        check (ai_response_source_language is null or ai_response_source_language in ('ko', 'en', 'ja'));

alter table question_comments
    alter column source_language drop default,
    add constraint chk_question_comments_source_language
        check (source_language in ('ko', 'en', 'ja'));
