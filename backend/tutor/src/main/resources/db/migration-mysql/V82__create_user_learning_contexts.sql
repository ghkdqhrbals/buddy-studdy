create table user_learning_contexts (
    user_id bigint not null,
    resume_markdown longtext null,
    interests_json longtext not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (user_id),
    constraint fk_user_learning_contexts_user
        foreign key (user_id) references users(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Per-user resume and interest context for personalized learning';
