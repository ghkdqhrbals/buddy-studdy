alter table questions
    add column source_language varchar(16) null after language,
    add column answer_source_language varchar(16) null after answer,
    add column ai_response_source_language varchar(16) null after explanation;

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
    modify source_language varchar(16) not null;

alter table question_comments
    add column source_language varchar(16) not null default 'ko' after body;

create table question_localizations (
    question_id bigint not null,
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
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (question_id, target_language),
    constraint fk_question_localizations_question
        foreign key (question_id) references questions(id) on delete cascade,
    index idx_question_localizations_target_status
        (target_language, status, updated_at, question_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table answer_localizations (
    question_id bigint not null,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    answer text,
    status varchar(16) not null default 'PENDING',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (question_id, target_language),
    constraint fk_answer_localizations_question
        foreign key (question_id) references questions(id) on delete cascade,
    index idx_answer_localizations_target_status
        (target_language, status, updated_at, question_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table grading_localizations (
    question_id bigint not null,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    feedback text,
    explanation text,
    assessment_json longtext,
    status varchar(16) not null default 'PENDING',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (question_id, target_language),
    constraint fk_grading_localizations_question
        foreign key (question_id) references questions(id) on delete cascade,
    index idx_grading_localizations_target_status
        (target_language, status, updated_at, question_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table question_comment_localizations (
    comment_id bigint not null,
    target_language varchar(16) not null,
    source_language varchar(16) not null,
    source_hash varchar(64) not null,
    body text,
    status varchar(16) not null default 'PENDING',
    provider varchar(64),
    translation_version int not null default 1,
    error text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (comment_id, target_language),
    constraint fk_comment_localizations_comment
        foreign key (comment_id) references question_comments(id) on delete cascade,
    index idx_comment_localizations_target_status
        (target_language, status, updated_at, comment_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

insert into question_localizations (
    question_id, target_language, source_language, source_hash,
    topic, question, hint, status, provider, translation_version,
    created_at, updated_at
)
select
    id,
    'en',
    source_language,
    sha2(concat_ws(char(31), topic, question, coalesce(hint, '')), 256),
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
on duplicate key update
    topic = values(topic),
    question = values(question),
    hint = values(hint),
    source_hash = values(source_hash),
    status = 'READY',
    updated_at = values(updated_at);
