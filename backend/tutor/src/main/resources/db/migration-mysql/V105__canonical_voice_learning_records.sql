-- Single-version cutover: this migration removes the old duplicated voice core columns.
-- Stop old API writers before applying it; an old application JAR cannot be rolled back onto V105.
-- Session transcripts/results/revisions and the legacy extension/translation identities are retained.
-- MySQL DDL commits independently: every core copy and its search projection precedes the DROP.

-- Calls accepted under the old private-only contract stay private even if summary recovery runs later.
-- New calls use the ordinary record default, still subject to the account's live allow_public_questions gate.
alter table voice_tutor_sessions
    add column records_default_public boolean not null default false
        comment 'Creation-time record publicity default; existing/private-only sessions remain false';
alter table voice_tutor_sessions
    alter column records_default_public set default true;

alter table questions
    drop check chk_questions_status,
    drop check chk_questions_source,
    add column record_type varchar(24) not null default 'QUESTION'
        comment 'Canonical record type. Values: QUESTION, VOICE_TUTOR',
    add column voice_record_id bigint null
        comment 'Internal voice extension ID; questions.id is the sole public/detail/comment record identity',
    modify column status varchar(32) not null
        comment 'Record lifecycle. Values: ungraded, grading, graded, failed, skipped, completed (voice only)',
    modify column source varchar(64) not null
        comment 'Creation source. Values: scheduled, manual, voice_tutor',
    add constraint uq_questions_voice_record unique (voice_record_id),
    add constraint fk_questions_voice_record foreign key (voice_record_id)
        references voice_study_learning_records(id) on delete cascade,
    add constraint chk_questions_record_type check (record_type in ('QUESTION', 'VOICE_TUTOR')),
    add constraint chk_questions_status check (status in ('ungraded', 'grading', 'graded', 'failed', 'skipped', 'completed')),
    add constraint chk_questions_source check (source in ('scheduled', 'manual', 'voice_tutor')),
    -- MySQL does not allow a CHECK to reference a column with cascading FK actions.
    -- The nullable voice reference is constrained by its FK/UNIQUE and exact typed application joins.
    add constraint chk_questions_voice_shape check (
        (record_type = 'QUESTION' and source <> 'voice_tutor' and status <> 'completed')
        or
        (record_type = 'VOICE_TUTOR' and source = 'voice_tutor'
            and status = 'completed' and device_id = '' and skipped_at is null
            and grading_request_id is null and grading_status is null and is_correct is null
            and difficulty_level between 1 and 10
            and (score is null or (score between 0 and 100 and answer is not null)))
    ),
    add index idx_questions_node_record_time (user_id, study_id, record_type, deleted_at, created_at desc, id desc);

-- Allocate canonical IDs from questions' own sequence. Never reuse v.id or MAX(id)+offset:
-- v.id can already equal an unrelated ordinary question ID. The UNIQUE voice_record_id is the map.
-- A removed/foreign live node becomes NULL, while v.study_id retains the exact frozen association.
-- No active-device lookup, answer generation/grading, question quota, or public preference is invoked.
insert into questions (
    device_id, user_id, study_id, record_type, voice_record_id,
    question, hint, topic, difficulty_level, scheduled_for, status, source,
    answer, score, is_correct, feedback, explanation,
    source_language, answer_source_language, ai_response_source_language,
    is_public, created_at, updated_at
)
select '', v.user_id, live.id, 'VOICE_TUTOR', v.id,
    v.question, null, v.topic, v.difficulty, v.occurred_at, 'completed', 'voice_tutor',
    v.answer, v.score, null, v.feedback, null,
    coalesce(nullif(json_unquote(json_extract(v.source_languages_json, '$.question')), 'null'), v.source_language),
    case when v.answer is not null then
        coalesce(nullif(json_unquote(json_extract(v.source_languages_json, '$.answer')), 'null'), v.source_language)
        else null end,
    case when v.feedback is not null then
        coalesce(nullif(json_unquote(json_extract(v.source_languages_json, '$.feedback')), 'null'), v.source_language)
        else null end,
    false, v.occurred_at, v.created_at
from voice_study_learning_records v
left join studies live on live.id = v.study_id and live.user_id = v.user_id
where not exists (select 1 from questions q where q.voice_record_id = v.id)
order by v.id;

-- Existing READY voice translations retain their exact record IDs, hashes and tokens.
-- Index original fallback text while a target is unavailable, matching the shared detail projection.
insert into question_search (
    question_id, language, topic, question, answer, feedback, explanation, updated_at
)
select q.id, languages.language, q.topic,
    coalesce(nullif(nullif(json_unquote(json_extract(l.fields_json, '$.question')), 'null'), ''), q.question),
    case when q.answer is null then null else
        coalesce(nullif(nullif(json_unquote(json_extract(l.fields_json, '$.answer')), 'null'), ''), q.answer) end,
    case when q.feedback is null then null else
        coalesce(nullif(nullif(json_unquote(json_extract(l.fields_json, '$.feedback')), 'null'), ''), q.feedback) end,
    coalesce(nullif(nullif(json_unquote(json_extract(l.fields_json, '$.depthSummary')), 'null'), ''), v.depth_summary),
    q.updated_at
from questions q
join voice_study_learning_records v on v.id = q.voice_record_id and v.user_id = q.user_id
cross join (select 'ko' as language union all select 'en' union all select 'ja') languages
left join voice_study_learning_localizations l on l.record_id = v.id
    and l.target_language = languages.language and l.status = 'READY' and l.source_hash = v.source_hash
    and not exists (
        select 1
        from json_table(json_keys(v.source_languages_json), '$[*]' columns (field_name varchar(64) path '$')) expected
        where nullif(nullif(trim(json_unquote(json_extract(l.fields_json, concat('$."', expected.field_name, '"')))), 'null'), '') is null
    )
where q.record_type = 'VOICE_TUTOR' and q.deleted_at is null;

-- The extension now owns only voice-specific evidence and the frozen study identity. Core text,
-- score, title, level, source language and occurrence time have exactly one authority in questions.
alter table voice_study_learning_records
    drop check chk_voice_study_record_score,
    drop check chk_voice_study_record_level,
    drop index idx_voice_study_record_node_time,
    drop index idx_voice_study_record_user_time,
    drop column question,
    drop column answer,
    drop column score,
    drop column feedback,
    drop column topic,
    drop column difficulty,
    drop column source_language,
    drop column occurred_at,
    add index idx_voice_study_record_frozen_node (user_id, study_id, id),
    comment = 'Typed voice evidence for canonical questions records; legacy ID is kept for translations only';

alter table questions comment = 'Canonical QUESTION and VOICE_TUTOR records with one identity for history, sharing and community';
