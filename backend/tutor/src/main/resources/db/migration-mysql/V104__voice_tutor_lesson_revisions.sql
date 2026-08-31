-- Separate the historical lesson anchor from the live FK, which becomes NULL on study deletion.
-- Previously deleted legacy anchors cannot be reconstructed from names or current tree structure.
alter table voice_tutor_sessions
    add column accepted_study_id bigint null
        comment 'Immutable accepted node identity; intentionally no live-study FK',
    add constraint chk_voice_tutor_accepted_study_id check (accepted_study_id is null or accepted_study_id > 0);

update voice_tutor_sessions set accepted_study_id = study_id where study_id is not null;

alter table voice_tutor_transcript_turns
    add column lesson_revision bigint not null default 0
        comment 'Server-owned response/input epoch; legacy 0, unknown binding -1, never transcript arrival time',
    add constraint chk_voice_tutor_turn_lesson_revision check (lesson_revision >= -1);

-- V102 initial snapshots remain immutable. Explicit setting changes append at most 32 rows per call
-- under the same session row lock; existing answers are resolved at their question turn's epoch.
create table voice_tutor_study_revisions (
    session_id varchar(36) not null,
    revision bigint not null,
    study_id bigint not null,
    parent_study_id bigint null,
    topic varchar(255) not null,
    difficulty int not null,
    captured_at datetime(6) not null,
    primary key (session_id, revision),
    constraint fk_voice_tutor_study_revision_session foreign key (session_id)
        references voice_tutor_sessions(id) on delete cascade,
    constraint chk_voice_tutor_study_revision_id check (study_id > 0),
    constraint chk_voice_tutor_study_revision_epoch check (revision > 0),
    constraint chk_voice_tutor_study_revision_level check (difficulty between 1 and 10),
    index idx_voice_tutor_study_revision_node (session_id, study_id, revision)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Bounded append-only metadata revisions for explicit in-call study setting changes';
