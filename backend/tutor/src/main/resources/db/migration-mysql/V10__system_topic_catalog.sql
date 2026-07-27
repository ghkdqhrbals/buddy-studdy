create table system_topic_catalog (
    id bigint auto_increment primary key,
    root_topic_key varchar(255) not null,
    root_topic_hash char(64) not null,
    parent_path_key varchar(1200) not null,
    parent_path_hash char(64) not null,
    topic_key varchar(255) not null,
    language varchar(16) not null,
    depth int not null,
    topic varchar(255) not null,
    sort_order int not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_system_topic_catalog_path_topic
        unique (root_topic_hash, parent_path_hash, topic_key, language, depth),
    index idx_system_topic_catalog_children
        (root_topic_hash, parent_path_hash, language, depth, sort_order, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
