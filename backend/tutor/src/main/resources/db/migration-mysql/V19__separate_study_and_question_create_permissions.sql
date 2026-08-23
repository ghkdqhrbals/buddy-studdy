insert into permissions (code, description, requires_active_account, created_at, updated_at)
select 'question:create', 'Create study questions', true, utc_timestamp(6), utc_timestamp(6)
where not exists (select 1 from permissions where code = 'question:create');

update permissions
set description = 'Create studies and topics',
    updated_at = utc_timestamp(6)
where code = 'study:create';

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select existing.role_id, question_permission.id, utc_timestamp(6), utc_timestamp(6)
from role_permissions existing
join permissions study_permission
  on study_permission.id = existing.permission_id
 and study_permission.code = 'study:create'
cross join permissions question_permission
where question_permission.code = 'question:create'
  and not exists (
      select 1
      from role_permissions granted
      where granted.role_id = existing.role_id
        and granted.permission_id = question_permission.id
  );

insert into permission_requirements (
    permission_id,
    requirement_type,
    requirement_key,
    operator,
    requirement_value,
    failure_code,
    effective_at,
    retired_at,
    created_at
)
select
    question_permission.id,
    requirement.requirement_type,
    requirement.requirement_key,
    requirement.operator,
    requirement.requirement_value,
    requirement.failure_code,
    requirement.effective_at,
    null,
    utc_timestamp(6)
from permission_requirements requirement
join permissions study_permission
  on study_permission.id = requirement.permission_id
 and study_permission.code = 'study:create'
cross join permissions question_permission
where question_permission.code = 'question:create'
  and requirement.retired_at is null
  and not exists (
      select 1
      from permission_requirements existing
      where existing.permission_id = question_permission.id
        and existing.requirement_type = requirement.requirement_type
        and existing.requirement_key = requirement.requirement_key
        and existing.operator = requirement.operator
        and existing.failure_code = requirement.failure_code
        and existing.retired_at is null
  );

update permission_requirements requirement
join permissions permission on permission.id = requirement.permission_id
set requirement.retired_at = utc_timestamp(6)
where permission.code = 'study:create'
  and requirement.requirement_type = 'QUOTA_AVAILABLE'
  and requirement.requirement_key = 'monthly_question'
  and requirement.retired_at is null;
