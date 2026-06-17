insert into permissions (code, description, requires_active_account, created_at, updated_at)
select v.code, v.description, v.requires_active_account, now(), now()
from (
    select 'notification:read' as code, 'Read notifications' as description, false as requires_active_account
    union all select 'notification:delete', 'Delete notifications', true
) v
where not exists (select 1 from permissions p where p.code = v.code);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in ('notification:read', 'notification:delete')
where r.code = 'REGISTERED_USER'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in ('notification:read', 'notification:delete')
where r.code = 'ADMIN'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);
