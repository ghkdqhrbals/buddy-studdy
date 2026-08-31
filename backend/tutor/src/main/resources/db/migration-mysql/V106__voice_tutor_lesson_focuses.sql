-- accepted_study_id remains the immutable creation request (NULL for discovery calls).
-- study_id is the live current focus; its nullable FK still preserves history on deletion.
create table voice_tutor_lesson_focuses (
    session_id varchar(36) not null,
    revision bigint not null,
    study_id bigint not null comment 'Historical selected node identity; intentionally no live-study FK',
    captured_at datetime(6) not null,
    primary key (session_id, revision),
    constraint fk_voice_tutor_lesson_focus_session foreign key (session_id)
        references voice_tutor_sessions(id) on delete cascade,
    constraint chk_voice_tutor_lesson_focus_revision check (revision >= 0),
    constraint chk_voice_tutor_lesson_focus_study check (study_id > 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Immutable explicit focus per server lesson epoch; discovery before the first selection is not node learning';

-- Do not reconstruct previously unknown/deleted legacy anchors from topic names.
insert into voice_tutor_lesson_focuses (session_id, revision, study_id, captured_at)
select id, 0, accepted_study_id, created_at
from voice_tutor_sessions where accepted_study_id is not null;
