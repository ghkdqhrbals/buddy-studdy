create table feedbacks (
    id bigint auto_increment primary key,
    user_id bigint,
    device_id varchar(191),
    content varchar(1000) not null,
    created_at datetime(6) not null,
    index idx_feedbacks_created (created_at, id),
    index idx_feedbacks_user_created (user_id, created_at, id),
    index idx_feedbacks_device_created (device_id, created_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
