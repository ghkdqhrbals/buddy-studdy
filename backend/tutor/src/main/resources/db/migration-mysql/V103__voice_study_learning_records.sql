-- A node-attached read model of verified voice exchanges, never ordinary questions.
-- The canonical transcript/result is retained; no quota, pending answer or score-stat row changes.
alter table voice_tutor_results
    add column learning_records_projected_at datetime(6) null;

create table voice_study_learning_records (
    id bigint auto_increment primary key,
    user_id bigint not null,
    session_id varchar(36) not null,
    study_id bigint not null comment 'Stable captured node ID; never matched by topic text',
    parent_study_id bigint null,
    topic varchar(255) not null,
    difficulty int not null,
    kind varchar(24) not null,
    question mediumtext not null,
    answer mediumtext null,
    score int null,
    feedback mediumtext null,
    strengths_json json not null,
    improvements_json json not null,
    depth_summary text not null,
    question_turn_id bigint not null,
    answer_turn_ids_json json not null,
    feedback_turn_ids_json json not null,
    source_language varchar(8) not null,
    source_languages_json json not null,
    source_hash varchar(64) not null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint uq_voice_study_record_source unique (session_id, question_turn_id),
    constraint fk_voice_study_record_session foreign key (session_id)
        references voice_tutor_sessions(id) on delete cascade,
    constraint chk_voice_study_record_kind check (kind in ('TUTOR_QUESTION', 'LEARNER_QUESTION')),
    constraint chk_voice_study_record_level check (difficulty between 1 and 10),
    constraint chk_voice_study_record_score check (
        score is null or (score between 0 and 100 and kind = 'TUTOR_QUESTION' and answer is not null)
    ),
    index idx_voice_study_record_node_time (user_id, study_id, occurred_at desc, id desc),
    index idx_voice_study_record_user_time (user_id, occurred_at desc, id desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Private source-backed voice learning records attached to saved study tree nodes';

create table voice_study_learning_localizations (
    record_id bigint not null,
    target_language varchar(8) not null,
    source_language varchar(8) not null,
    source_hash varchar(64) not null,
    request_token varchar(36) not null,
    status varchar(16) not null,
    fields_json json null,
    provider varchar(64) null,
    error_message varchar(255) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (record_id, target_language),
    constraint fk_voice_study_localization_record foreign key (record_id)
        references voice_study_learning_records(id) on delete cascade,
    constraint chk_voice_study_localization_language check (target_language in ('ko', 'en', 'ja')),
    constraint chk_voice_study_localization_status check (status in ('PENDING', 'READY', 'FAILED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Translations only; original voice learning record content is never overwritten';
