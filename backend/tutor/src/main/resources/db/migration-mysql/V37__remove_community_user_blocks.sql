delete role_permission
from role_permissions role_permission
join permissions permission on permission.id = role_permission.permission_id
where permission.code = 'public-user:block';

delete requirement
from permission_requirements requirement
join permissions permission on permission.id = requirement.permission_id
where permission.code = 'public-user:block';

delete from permissions where code = 'public-user:block';

drop table if exists user_blocks;
