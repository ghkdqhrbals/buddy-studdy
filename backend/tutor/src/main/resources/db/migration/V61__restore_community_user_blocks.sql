create table user_blocks (
    id bigserial primary key,
    blocker_user_id bigint not null,
    blocked_user_id bigint not null,
    created_at timestamp with time zone not null,
    constraint uq_user_blocks_pair unique (blocker_user_id, blocked_user_id),
    constraint ck_user_blocks_not_self check (blocker_user_id <> blocked_user_id)
);

create index idx_user_blocks_blocked
    on user_blocks (blocked_user_id, blocker_user_id);

comment on table user_blocks is 'User-level community visibility blocks';
comment on column user_blocks.blocker_user_id is 'User who owns this visibility block';
comment on column user_blocks.blocked_user_id is 'Community author hidden from the blocker';
comment on column user_blocks.created_at is 'UTC instant when the block was created';

insert into permissions (code, description, requires_active_account, created_at, updated_at)
values ('public-user:block', 'Block community users', true, now(), now())
on conflict (code) do update
set description = excluded.description,
    requires_active_account = excluded.requires_active_account,
    updated_at = excluded.updated_at;

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select role.id, permission.id, now(), now()
from roles role
join permissions permission on permission.code = 'public-user:block'
where role.code = 'REGISTERED_USER'
  and not exists (
      select 1
      from role_permissions role_permission
      where role_permission.role_id = role.id
        and role_permission.permission_id = permission.id
  );
