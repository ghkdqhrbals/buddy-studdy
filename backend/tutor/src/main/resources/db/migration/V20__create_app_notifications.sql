create table if not exists app_notifications (
    id bigserial primary key,
    event_id varchar(80) not null,
    user_id bigint not null,
    actor_user_id bigint,
    type varchar(64) not null,
    title varchar(160) not null,
    body text not null,
    thread_type varchar(64),
    thread_id varchar(120),
    deep_link varchar(500),
    metadata_json text,
    should_push boolean not null default false,
    push_claimed_at timestamp with time zone,
    push_sent_at timestamp with time zone,
    push_error text,
    read_at timestamp with time zone,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_app_notifications_event_id unique (event_id)
);

create index if not exists idx_app_notifications_user_visible_created
    on app_notifications (user_id, deleted_at, created_at desc, id desc);

create index if not exists idx_app_notifications_user_unread
    on app_notifications (user_id, read_at, deleted_at);

create index if not exists idx_app_notifications_thread
    on app_notifications (thread_type, thread_id);
