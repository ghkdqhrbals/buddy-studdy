create table service_maintenance_windows (
    id bigint not null auto_increment,
    title_ko varchar(120) not null,
    title_en varchar(120) not null,
    title_ja varchar(120) not null,
    message_ko varchar(1000) not null,
    message_en varchar(1000) not null,
    message_ja varchar(1000) not null,
    starts_at datetime(6) not null,
    ends_at datetime(6) null,
    terminated_at datetime(6) null,
    created_by varchar(100) not null,
    terminated_by varchar(100) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6) on update current_timestamp(6),
    primary key (id),
    index idx_maintenance_effective (terminated_at, starts_at, ends_at),
    index idx_maintenance_created (created_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
