alter table questions add column parent_record_id bigint;
alter table questions add column root_record_id bigint;
alter table questions add column follow_up_depth integer not null default 0;
create unique index uq_questions_follow_up_parent on questions(parent_record_id);
create unique index uq_questions_follow_up_root_depth on questions(root_record_id, follow_up_depth);
create index idx_questions_owner_thread on questions(user_id, root_record_id, follow_up_depth);
alter table if exists question_generation_sagas add column parent_record_id bigint;
alter table if exists question_generation_sagas add column root_record_id bigint;
alter table if exists question_generation_sagas add column follow_up_depth integer not null default 0;

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
