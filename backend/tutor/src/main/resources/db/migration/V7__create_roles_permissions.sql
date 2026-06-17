create table if not exists roles (
    id bigserial primary key,
    code varchar(64) not null unique,
    name varchar(120) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_roles_code on roles (code);

create table if not exists permissions (
    id bigserial primary key,
    code varchar(120) not null unique,
    description varchar(255) not null,
    requires_active_account boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_permissions_code on permissions (code);

create table if not exists user_roles (
    id bigserial primary key,
    user_id bigint not null,
    role_id bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_user_roles_user_role unique (user_id, role_id)
);

create index if not exists idx_user_roles_user_id on user_roles (user_id);
create index if not exists idx_user_roles_role_id on user_roles (role_id);

create table if not exists role_permissions (
    id bigserial primary key,
    role_id bigint not null,
    permission_id bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_role_permissions_role_permission unique (role_id, permission_id)
);

create index if not exists idx_role_permissions_role_id on role_permissions (role_id);
create index if not exists idx_role_permissions_permission_id on role_permissions (permission_id);

insert into roles (code, name, created_at, updated_at)
select 'ANONYMOUS_USER', 'Anonymous User', now(), now()
where not exists (select 1 from roles where code = 'ANONYMOUS_USER');
insert into roles (code, name, created_at, updated_at)
select 'REGISTERED_USER', 'Registered User', now(), now()
where not exists (select 1 from roles where code = 'REGISTERED_USER');
insert into roles (code, name, created_at, updated_at)
select 'TESTER', 'Tester', now(), now()
where not exists (select 1 from roles where code = 'TESTER');
insert into roles (code, name, created_at, updated_at)
select 'MODERATOR', 'Moderator', now(), now()
where not exists (select 1 from roles where code = 'MODERATOR');
insert into roles (code, name, created_at, updated_at)
select 'ADMIN', 'Admin', now(), now()
where not exists (select 1 from roles where code = 'ADMIN');

insert into permissions (code, description, requires_active_account, created_at, updated_at)
select v.code, v.description, v.requires_active_account, now(), now()
from (
    select 'device:register' as code, 'Register device' as description, false as requires_active_account
    union all select 'auth:login', 'Login and issue access token', false
    union all select 'profile:read', 'Read profile', false
    union all select 'profile:update', 'Update profile', true
    union all select 'profile:withdraw', 'Withdraw profile', true
    union all select 'study:read', 'Read studies', false
    union all select 'study:create', 'Create studies or questions', true
    union all select 'study:update', 'Update studies', true
    union all select 'study:delete', 'Delete studies', true
    union all select 'record:read', 'Read records', false
    union all select 'record:update', 'Update records', true
    union all select 'record:delete', 'Delete records', true
    union all select 'record:publish', 'Publish records', true
    union all select 'stats:read', 'Read statistics', false
    union all select 'public-question:read', 'Read public questions', false
    union all select 'public-question:like', 'Like public questions', true
    union all select 'public-question:comment', 'Comment on public questions', true
    union all select 'public-question:report', 'Report public questions', true
    union all select 'comment:delete', 'Delete comments', true
    union all select 'debug:read', 'Read debug logs', false
    union all select 'test-push:send', 'Send test push', true
    union all select 'admin:read', 'Read admin resources', false
    union all select 'admin:write', 'Write admin resources', true
) v
where not exists (select 1 from permissions p where p.code = v.code);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in ('device:register', 'auth:login', 'profile:read', 'public-question:read')
where r.code = 'ANONYMOUS_USER'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in (
    'device:register',
    'auth:login',
    'profile:read',
    'profile:update',
    'profile:withdraw',
    'study:read',
    'study:create',
    'study:update',
    'study:delete',
    'record:read',
    'record:update',
    'record:delete',
    'record:publish',
    'stats:read',
    'public-question:read',
    'public-question:like',
    'public-question:comment',
    'public-question:report',
    'comment:delete'
)
where r.code = 'REGISTERED_USER'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in ('debug:read', 'test-push:send')
where r.code = 'TESTER'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in ('public-question:read', 'comment:delete', 'admin:read')
where r.code = 'MODERATOR'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
cross join permissions p
where r.code = 'ADMIN'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into user_roles (user_id, role_id, created_at, updated_at)
select u.id, r.id, now(), now()
from users u
join roles r on r.code = 'ANONYMOUS_USER'
where u.status = 'ANONYMOUS'
  and not exists (select 1 from user_roles ur where ur.user_id = u.id and ur.role_id = r.id);

insert into user_roles (user_id, role_id, created_at, updated_at)
select u.id, r.id, now(), now()
from users u
join roles r on r.code = 'REGISTERED_USER'
where u.status in ('ACTIVE', 'SUSPENDED', 'WITHDRAWN')
  and not exists (select 1 from user_roles ur where ur.user_id = u.id and ur.role_id = r.id);
