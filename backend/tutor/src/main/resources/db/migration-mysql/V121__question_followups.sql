alter table questions
    add column parent_record_id bigint null comment 'Immediate earlier question in a private follow-up thread',
    add column root_record_id bigint null comment 'Original independently graded question owning this thread',
    add column follow_up_depth int not null default 0 comment 'Original question 0; coached follow-up 1 or 2',
    modify column source varchar(64) not null comment 'Question origin: scheduled, manual, voice_tutor, follow_up, custom_question',
    drop check chk_questions_source,
    add constraint chk_questions_source check (source in ('scheduled', 'manual', 'voice_tutor', 'follow_up', 'custom_question')),
    add constraint chk_questions_follow_up_private check (source <> 'follow_up' or is_public = false),
    add constraint chk_questions_follow_up_depth check (follow_up_depth between 0 and 2),
    add constraint chk_questions_follow_up_lineage check (
        (source = 'follow_up' and parent_record_id is not null and root_record_id is not null and follow_up_depth in (1, 2))
        or (source <> 'follow_up' and parent_record_id is null and root_record_id is null and follow_up_depth = 0)
    ),
    add unique index uq_questions_follow_up_parent (parent_record_id),
    add unique index uq_questions_follow_up_root_depth (root_record_id, follow_up_depth),
    add index idx_questions_owner_thread (user_id, root_record_id, follow_up_depth);

alter table question_generation_sagas
    add column parent_record_id bigint null comment 'Immediate earlier question in a private follow-up thread',
    add column root_record_id bigint null comment 'Original independently graded question owning this thread',
    add column follow_up_depth int not null default 0 comment 'Original question 0; coached follow-up 1 or 2',
    drop check chk_question_generation_saga_source,
    add constraint chk_question_generation_saga_source check (source in ('MANUAL', 'SCHEDULED', 'FOLLOW_UP'));

-- Follow-up admission checks the same authorization requirements as question creation,
-- with quota reserved transactionally after idempotency lookup so the final unit can be replayed.
insert into permissions (code, description, requires_active_account, created_at, updated_at)
select 'question:follow-up', 'Create private follow-up questions', true, current_timestamp, current_timestamp
where not exists (select 1 from permissions where code = 'question:follow-up');

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select existing.role_id, target.id, current_timestamp, current_timestamp
from role_permissions existing
join permissions source on source.id = existing.permission_id and source.code = 'question:create'
cross join permissions target
where target.code = 'question:follow-up'
  and not exists (select 1 from role_permissions granted where granted.role_id = existing.role_id and granted.permission_id = target.id);

insert into permission_requirements (
    permission_id, requirement_type, requirement_key, operator, requirement_value,
    failure_code, effective_at, retired_at, created_at
)
select target.id, requirement.requirement_type, requirement.requirement_key, requirement.operator,
    requirement.requirement_value, requirement.failure_code, requirement.effective_at, null, current_timestamp
from permission_requirements requirement
join permissions source on source.id = requirement.permission_id and source.code = 'question:create'
cross join permissions target
where target.code = 'question:follow-up' and requirement.retired_at is null
  and requirement.requirement_type <> 'QUOTA_AVAILABLE';
