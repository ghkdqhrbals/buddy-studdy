create table user_topic_subscriptions (
    user_id bigint not null,
    topic_key varchar(120) collate utf8mb4_bin not null,
    topic varchar(120) not null,
    sort_order int not null,
    primary key (user_id, topic_key),
    constraint fk_user_topic_subscriptions_user foreign key (user_id) references users(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Account-owned public-feed topic subscriptions, independent of learning context';
