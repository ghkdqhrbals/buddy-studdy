create table if not exists users (
    id bigserial primary key,
    provider varchar(32) not null,
    provider_id varchar(191) not null,
    password_hash varchar(64),
    status varchar(32) not null,
    email varchar(320) not null,
    display_name varchar(120) not null,
    avatar_url varchar(1000),
    avatar_symbol_name varchar(64) not null,
    avatar_color_seed varchar(64) not null,
    bio varchar(500) not null,
    allow_public_questions boolean not null,
    app_language varchar(16) not null default 'ko',
    openai_api_key_cipher text,
    openai_model varchar(64) not null default 'gpt-5.4',
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_users_provider_provider_id unique (provider, provider_id)
);

create index if not exists idx_users_provider_id on users (provider_id);

create table if not exists devices (
    id bigserial primary key,
    device_id varchar(191) not null unique,
    client_secret_hash varchar(191) not null,
    user_id bigint,
    google_session_expires_at timestamp with time zone,
    apns_token varchar(191) not null,
    platform varchar(32) not null,
    apns_environment varchar(32) not null,
    language varchar(16) not null,
    timezone varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null
);

create index if not exists idx_devices_device_id on devices (device_id);
create index if not exists idx_devices_user_id on devices (user_id);

create table if not exists user_devices (
    id bigserial primary key,
    user_id bigint not null,
    device_id varchar(191) not null,
    session_expires_at timestamp with time zone,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    constraint uq_user_devices_user_device unique (user_id, device_id)
);

create index if not exists idx_user_devices_user_id on user_devices (user_id);
create index if not exists idx_user_devices_device_id on user_devices (device_id);

create table if not exists schedules (
    id bigserial primary key,
    device_id varchar(191) not null,
    user_id bigint,
    topic varchar(255) not null,
    difficulty_level integer not null,
    interval_minutes integer not null,
    enabled boolean not null,
    notification_sound varchar(64),
    custom_prompt text not null,
    app_language varchar(16) not null,
    openai_model varchar(64) not null,
    max_history_count integer not null,
    is_question_public boolean not null,
    openai_api_key_cipher text,
    next_due_at timestamp with time zone,
    last_sent_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint idx_schedules_device_user_topic unique (device_id, user_id, topic)
);

create index if not exists idx_schedules_due on schedules (enabled, next_due_at);
create index if not exists idx_schedules_device_user on schedules (device_id, user_id);

create table if not exists questions (
    id bigserial primary key,
    device_id varchar(191) not null,
    user_id bigint,
    question text not null,
    hint text,
    topic varchar(255) not null,
    difficulty_level integer not null,
    scheduled_for timestamp with time zone not null,
    sent_at timestamp with time zone,
    status varchar(32) not null,
    error text,
    answer text,
    score integer,
    is_correct boolean,
    feedback text,
    explanation text,
    answered_at timestamp with time zone,
    graded_at timestamp with time zone,
    skipped_at timestamp with time zone,
    deleted_at timestamp with time zone,
    source varchar(64) not null,
    is_public boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_questions_user_created on questions (user_id, created_at);
create index if not exists idx_questions_device_created on questions (device_id, created_at);
create index if not exists idx_questions_public on questions (is_public, deleted_at, created_at);
create index if not exists idx_questions_pending_study on questions (device_id, user_id, topic, deleted_at, skipped_at, score, status);

create table if not exists question_stats (
    question_id bigint primary key,
    like_count integer not null,
    comment_count integer not null,
    view_count integer not null,
    verified_at timestamp with time zone,
    updated_at timestamp with time zone not null
);

create table if not exists question_likes (
    id bigserial primary key,
    question_id bigint not null,
    user_id bigint not null,
    created_at timestamp with time zone not null,
    constraint uq_question_likes_question_user unique (question_id, user_id)
);

create table if not exists question_comments (
    id bigserial primary key,
    question_id bigint not null,
    user_id bigint not null,
    body varchar(1000) not null,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table if not exists reports (
    id bigserial primary key,
    question_id bigint,
    reporter_device_id varchar(191),
    reporter_user_id bigint,
    reason varchar(120) not null,
    message varchar(1000) not null,
    created_at timestamp with time zone not null
);
