create table user_learning_contexts (
    user_id bigint primary key,
    resume_markdown text null,
    interests_json text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_user_learning_contexts_user
        foreign key (user_id) references users(id) on delete cascade
);

comment on table user_learning_contexts is
    'Per-user resume and interest context for personalized learning';
