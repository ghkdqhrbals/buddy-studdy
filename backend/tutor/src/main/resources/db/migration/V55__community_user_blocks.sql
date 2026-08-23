create table if not exists user_blocks (
    id bigserial primary key,
    blocker_user_id bigint not null,
    blocked_user_id bigint not null,
    created_at timestamp with time zone not null,
    constraint uq_user_blocks_pair unique (blocker_user_id, blocked_user_id),
    constraint ck_user_blocks_not_self check (blocker_user_id <> blocked_user_id)
);

create index if not exists idx_user_blocks_blocker
    on user_blocks (blocker_user_id, blocked_user_id);

insert into permissions (code, description, requires_active_account, created_at, updated_at)
select 'public-user:block', 'Block community users', true, now(), now()
where not exists (select 1 from permissions where code = 'public-user:block');

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code = 'public-user:block'
where r.code = 'REGISTERED_USER'
  and not exists (
      select 1
      from role_permissions rp
      where rp.role_id = r.id and rp.permission_id = p.id
  );
