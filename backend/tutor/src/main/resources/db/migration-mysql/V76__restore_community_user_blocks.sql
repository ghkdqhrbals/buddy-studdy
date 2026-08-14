create table user_blocks (
    id bigint auto_increment primary key,
    blocker_user_id bigint not null
        comment 'User who owns this visibility block',
    blocked_user_id bigint not null
        comment 'Community author hidden from the blocker',
    created_at datetime(6) not null
        comment 'UTC instant when the block was created',
    constraint uq_user_blocks_pair unique (blocker_user_id, blocked_user_id),
    constraint ck_user_blocks_not_self check (blocker_user_id <> blocked_user_id),
    index idx_user_blocks_blocked (blocked_user_id, blocker_user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='User-level community visibility blocks';

insert into permissions (code, description, requires_active_account, created_at, updated_at)
values ('public-user:block', 'Block community users', true, utc_timestamp(6), utc_timestamp(6))
on duplicate key update
    description = values(description),
    requires_active_account = values(requires_active_account),
    updated_at = values(updated_at);

insert ignore into role_permissions (role_id, permission_id, created_at, updated_at)
select role.id, permission.id, utc_timestamp(6), utc_timestamp(6)
from roles role
join permissions permission on permission.code = 'public-user:block'
where role.code = 'REGISTERED_USER';
