delete from role_permissions
where permission_id = (
    select id from permissions where code = 'public-user:block'
);

delete from permission_requirements
where permission_id = (
    select id from permissions where code = 'public-user:block'
);

delete from permissions where code = 'public-user:block';

drop table if exists user_blocks;
