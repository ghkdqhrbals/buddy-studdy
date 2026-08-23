alter table questions
    add column if not exists source_language varchar(16),
    add column if not exists answer_source_language varchar(16),
    add column if not exists ai_response_source_language varchar(16);

update questions
set source_language = case
    when lower(language) like 'ja%' then 'ja'
    when lower(language) like 'en%' then 'en'
    else 'ko'
end
where source_language is null;

update questions
set answer_source_language = source_language
where answer_source_language is null
  and answer is not null
  and trim(answer) <> '';

update questions
set ai_response_source_language = source_language
where ai_response_source_language is null
  and (
      (feedback is not null and trim(feedback) <> '')
      or (explanation is not null and trim(explanation) <> '')
  );

alter table questions
    alter column source_language set not null;

alter table question_comments
    add column if not exists source_language varchar(16) not null default 'ko';

create table if not exists question_localizations (
    question_id bigint not null references questions(id) on delete cascade,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    topic varchar(255) not null,
    question text not null,
    hint text,
    status varchar(16) not null default 'READY',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (question_id, target_language)
);

create table if not exists answer_localizations (
    question_id bigint not null references questions(id) on delete cascade,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    answer text,
    status varchar(16) not null default 'PENDING',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (question_id, target_language)
);

create table if not exists grading_localizations (
    question_id bigint not null references questions(id) on delete cascade,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    feedback text,
    explanation text,
    assessment_json text,
    status varchar(16) not null default 'PENDING',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (question_id, target_language)
);

create table if not exists question_comment_localizations (
    comment_id bigint not null references question_comments(id) on delete cascade,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    body text,
    status varchar(16) not null default 'PENDING',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (comment_id, target_language)
);

create index if not exists idx_question_localizations_target_status
    on question_localizations (target_language, status, updated_at, question_id);
create index if not exists idx_answer_localizations_target_status
    on answer_localizations (target_language, status, updated_at, question_id);
create index if not exists idx_grading_localizations_target_status
    on grading_localizations (target_language, status, updated_at, question_id);
create index if not exists idx_comment_localizations_target_status
    on question_comment_localizations (target_language, status, updated_at, comment_id);

insert into question_localizations (
    question_id, target_language, source_language, source_hash,
    topic, question, hint, status, provider, translation_version,
    created_at, updated_at
)
select
    id,
    'en',
    source_language,
    md5(topic || chr(31) || question || chr(31) || coalesce(hint, '')),
    coalesce(topic_en, topic),
    question_en,
    hint_en,
    'READY',
    'legacy',
    1,
    created_at,
    updated_at
from questions
where translation_status = 'READY'
  and question_en is not null
on conflict (question_id, target_language) do update set
    topic = excluded.topic,
    question = excluded.question,
    hint = excluded.hint,
    source_hash = excluded.source_hash,
    status = 'READY',
    updated_at = excluded.updated_at;
