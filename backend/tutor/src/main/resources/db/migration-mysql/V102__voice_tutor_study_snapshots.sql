create table voice_tutor_study_snapshots (
    session_id varchar(36) not null
        comment 'Owner-checked voice lesson; deleting the lesson also removes these snapshots',
    study_id bigint not null
        comment 'Original saved node identity; intentionally not a live-study FK so history survives topic deletion',
    parent_study_id bigint null
        comment 'Saved parent identity when this node was first supplied to the lesson',
    topic varchar(255) not null
        comment 'Untrusted learner-authored title copied from the owned study or accepted session',
    difficulty int not null
        comment 'Immutable configured difficulty 1 through 10 used for this lesson node',
    captured_at datetime(6) not null
        comment 'UTC instant of the first successful session-scoped capture',
    primary key (session_id, study_id),
    constraint fk_voice_tutor_study_snapshot_session foreign key (session_id)
        references voice_tutor_sessions(id) on delete cascade,
    constraint chk_voice_tutor_study_snapshot_id check (study_id > 0),
    constraint chk_voice_tutor_study_snapshot_level check (difficulty between 1 and 10)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Bounded study metadata actually supplied to a private voice lesson, not question or growth statistics';
