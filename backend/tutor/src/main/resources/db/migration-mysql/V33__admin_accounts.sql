create table admin_accounts (
    id bigint auto_increment primary key,
    username varchar(64) not null,
    display_name varchar(100) not null,
    password_hash varchar(100) not null,
    status varchar(16) not null default 'ACTIVE',
    last_login_at datetime(6) null,
    created_by varchar(64) not null,
    updated_by varchar(64) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    unique key uk_admin_accounts_username (username),
    index idx_admin_accounts_status_username (status, username),
    constraint chk_admin_accounts_status check (status in ('ACTIVE', 'DISABLED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
  comment='Monitoring console administrator accounts';
