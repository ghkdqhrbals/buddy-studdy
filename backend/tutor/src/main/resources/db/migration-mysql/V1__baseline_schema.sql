create table users (
    id bigint auto_increment primary key,
    provider varchar(32) not null,
    provider_id varchar(191) not null,
    password_hash varchar(64),
    status varchar(32) not null,
    email varchar(320) not null,
    display_name varchar(120) not null,
    avatar_url varchar(1000),
    avatar_symbol_name varchar(64) not null default '',
    avatar_color_seed varchar(64) not null default '',
    bio varchar(500) not null default '',
    allow_public_questions boolean not null default true,
    openai_api_key_cipher text,
    openai_model varchar(64) not null default 'gpt-5.4',
    app_language varchar(16) not null default 'ko',
    free_system_question_count int not null default 0,
    avatar_mode varchar(32) not null default 'BUILDER',
    avatar_config text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_users_provider_provider_id unique (provider, provider_id),
    index idx_users_provider_id (provider_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table devices (
    id bigint auto_increment primary key,
    device_id varchar(191) not null,
    client_secret_hash varchar(191) not null,
    user_id bigint,
    google_session_expires_at datetime(6),
    apns_token varchar(191) not null,
    platform varchar(32) not null,
    apns_environment varchar(32) not null,
    language varchar(16) not null,
    timezone varchar(64) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    last_seen_at datetime(6) not null,
    constraint uq_devices_device_id unique (device_id),
    index idx_devices_user_id (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_devices (
    id bigint auto_increment primary key,
    user_id bigint not null,
    device_id varchar(191) not null,
    session_expires_at datetime(6),
    last_login_at datetime(6),
    last_seen_at datetime(6) not null,
    logged_out_at datetime(6),
    revoked_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_user_devices_user_device unique (user_id, device_id),
    index idx_user_devices_user_id (user_id),
    index idx_user_devices_device_id (device_id),
    index idx_user_devices_active_user (user_id, logged_out_at, revoked_at, session_expires_at),
    index idx_user_devices_active_device (device_id, logged_out_at, revoked_at, session_expires_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table studies (
    id bigint auto_increment primary key,
    device_id varchar(191) not null,
    user_id bigint not null,
    topic varchar(255) not null,
    difficulty_level int not null,
    interval_minutes int not null,
    enabled boolean not null,
    notification_sound varchar(64),
    custom_prompt text not null,
    openai_model varchar(64) not null,
    max_history_count int not null,
    next_due_at datetime(6),
    schedule_claimed_until datetime(6),
    last_sent_at datetime(6),
    last_error text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_studies_user_topic unique (user_id, topic),
    index idx_studies_due (enabled, next_due_at),
    index idx_studies_schedule_claim (enabled, next_due_at, schedule_claimed_until),
    index idx_studies_user_updated (user_id, updated_at),
    index idx_studies_device_user (device_id, user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table study_question_concepts (
    id bigint auto_increment primary key,
    study_id bigint not null,
    concept_key varchar(255) not null,
    concept_name varchar(255) not null,
    display_order int not null,
    parent_concept_id bigint,
    depth int not null,
    path varchar(512) not null,
    concept_path varchar(2048) not null,
    leaf boolean not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_study_question_concepts_study_path unique (study_id, path),
    constraint fk_study_question_concepts_study foreign key (study_id) references studies(id) on delete cascade,
    constraint fk_study_question_concepts_parent foreign key (parent_concept_id) references study_question_concepts(id) on delete set null,
    index idx_study_question_concepts_study_order (study_id, display_order, id),
    index idx_study_question_concepts_study_tree_order (study_id, parent_concept_id, display_order, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table questions (
    id bigint auto_increment primary key,
    device_id varchar(191) not null,
    user_id bigint,
    study_id bigint,
    question text not null,
    hint text,
    topic varchar(255) not null,
    difficulty_level int not null,
    scheduled_for datetime(6) not null,
    sent_at datetime(6),
    status varchar(32) not null,
    error text,
    answer text,
    score int,
    is_correct boolean,
    feedback text,
    explanation text,
    answered_at datetime(6),
    graded_at datetime(6),
    skipped_at datetime(6),
    deleted_at datetime(6),
    source varchar(64) not null,
    is_public boolean not null default true,
    language varchar(16) not null default 'ko',
    concept_id bigint,
    concept_key varchar(255),
    angle_key varchar(255),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_questions_device_created (device_id, created_at),
    index idx_questions_user_created (user_id, created_at),
    index idx_questions_study_created (study_id, created_at),
    index idx_questions_pending_study_v2 (study_id, deleted_at, skipped_at, score, status),
    index idx_questions_public (is_public, deleted_at, score, created_at, id),
    index idx_questions_stats_refresh (user_id, difficulty_level, answered_at, created_at),
    index idx_questions_user_topic_graded_latest (user_id, topic, deleted_at, score, answered_at, created_at, id),
    index idx_questions_study_concept_angle (study_id, concept_id, angle_key, created_at),
    fulltext index ft_questions_public_text (topic, question, answer, feedback, explanation)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table question_stats (
    question_id bigint primary key,
    like_count int not null default 0,
    comment_count int not null default 0,
    view_count int not null default 0,
    verified_at datetime(6),
    updated_at datetime(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table question_likes (
    id bigint auto_increment primary key,
    question_id bigint not null,
    user_id bigint not null,
    created_at datetime(6) not null,
    constraint uq_question_likes_question_user unique (question_id, user_id),
    index idx_question_likes_user_question (user_id, question_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table question_comments (
    id bigint auto_increment primary key,
    question_id bigint not null,
    user_id bigint not null,
    body varchar(1000) not null,
    deleted_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_question_comments_question_active_created (question_id, deleted_at, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table question_embeddings (
    question_id bigint primary key,
    user_id bigint not null,
    study_id bigint not null,
    topic varchar(255) not null,
    topic_key varchar(255) not null,
    question text not null,
    embedding text not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_question_embeddings_question foreign key (question_id) references questions(id) on delete cascade,
    index idx_question_embeddings_study_topic_created (study_id, topic_key, created_at, question_id),
    index idx_question_embeddings_user_topic_created (user_id, topic_key, created_at, question_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table study_question_coverage (
    id bigint auto_increment primary key,
    study_id bigint not null,
    concept_id bigint not null,
    angle_key varchar(255) not null,
    angle_name varchar(255) not null,
    asked_count bigint not null default 0,
    answer_count bigint not null default 0,
    correct_count bigint not null default 0,
    score_sum bigint not null default 0,
    last_asked_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_study_question_coverage_concept_angle unique (concept_id, angle_key),
    constraint fk_study_question_coverage_study foreign key (study_id) references studies(id) on delete cascade,
    constraint fk_study_question_coverage_concept foreign key (concept_id) references study_question_concepts(id) on delete cascade,
    index idx_study_question_coverage_pick (study_id, asked_count, last_asked_at, id),
    index idx_study_question_coverage_study (study_id, concept_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table study_question_jobs (
    id bigint auto_increment primary key,
    study_id bigint not null,
    device_id varchar(191) not null,
    user_id bigint not null,
    scheduled_at datetime(6) not null,
    status varchar(32) not null,
    attempt_count int not null default 0,
    locked_at datetime(6),
    locked_by varchar(128),
    completed_at datetime(6),
    canceled_at datetime(6),
    last_error text,
    created_question_id bigint,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_study_question_jobs_due (status, scheduled_at, id),
    index idx_study_question_jobs_study_status (study_id, status, scheduled_at),
    index idx_study_question_jobs_user (user_id, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table question_push_outbox (
    id bigint auto_increment primary key,
    record_id bigint not null,
    device_id varchar(191) not null,
    user_id bigint,
    study_id bigint,
    question text not null,
    expected_answer_hint text,
    topic varchar(255) not null,
    difficulty_level int not null,
    language varchar(16) not null,
    sound varchar(64),
    interval_minutes int not null,
    status varchar(32) not null,
    attempts int not null default 0,
    next_attempt_at datetime(6) not null,
    published_at datetime(6),
    last_error text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_question_push_outbox_pending (status, next_attempt_at, created_at),
    index idx_question_push_outbox_record (record_id),
    index idx_question_push_outbox_study (study_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table app_notifications (
    id bigint auto_increment primary key,
    event_id varchar(80) not null,
    user_id bigint,
    device_id varchar(191),
    actor_user_id bigint,
    type varchar(64) not null,
    title varchar(160) not null,
    body text not null,
    thread_type varchar(64),
    thread_id varchar(120),
    deep_link varchar(500),
    metadata_json text,
    should_push boolean not null default false,
    push_claimed_at datetime(6),
    push_sent_at datetime(6),
    push_error text,
    read_at datetime(6),
    deleted_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_app_notifications_event_id unique (event_id),
    index idx_app_notifications_user_visible_created (user_id, deleted_at, created_at, id),
    index idx_app_notifications_user_unread (user_id, read_at, deleted_at),
    index idx_app_notifications_device_visible_created (device_id, deleted_at, created_at, id),
    index idx_app_notifications_device_unread (device_id, read_at, deleted_at),
    index idx_app_notifications_thread (thread_type, thread_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table reports (
    id bigint auto_increment primary key,
    question_id bigint,
    reporter_device_id varchar(191),
    reporter_user_id bigint,
    reason varchar(120) not null,
    message varchar(1000) not null,
    created_at datetime(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table roles (
    id bigint auto_increment primary key,
    code varchar(64) not null,
    name varchar(120) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_roles_code unique (code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table permissions (
    id bigint auto_increment primary key,
    code varchar(120) not null,
    description varchar(255) not null,
    requires_active_account boolean not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_permissions_code unique (code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_roles (
    id bigint auto_increment primary key,
    user_id bigint not null,
    role_id bigint not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_user_roles_user_role unique (user_id, role_id),
    index idx_user_roles_user_id (user_id),
    index idx_user_roles_role_id (role_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table role_permissions (
    id bigint auto_increment primary key,
    role_id bigint not null,
    permission_id bigint not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_role_permissions_role_permission unique (role_id, permission_id),
    index idx_role_permissions_role_id (role_id),
    index idx_role_permissions_permission_id (permission_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table terms (
    id bigint auto_increment primary key,
    code varchar(120) not null,
    version varchar(32) not null,
    locale varchar(16) not null,
    title varchar(255) not null,
    url varchar(1000) not null,
    content_hash varchar(128) not null,
    effective_at datetime(6) not null,
    retired_at datetime(6),
    required boolean not null default false,
    mutable boolean not null default true,
    created_at datetime(6) not null default current_timestamp(6),
    constraint uq_terms_code_version_locale unique (code, version, locale),
    index idx_terms_active (code, retired_at, effective_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_term_agreements (
    id bigint auto_increment primary key,
    user_id bigint,
    device_id varchar(191),
    terms_id bigint not null,
    action varchar(32) not null,
    source varchar(32) not null,
    ip_address varchar(64),
    user_agent varchar(1000),
    app_version varchar(64),
    created_at datetime(6) not null,
    constraint fk_user_term_agreements_terms foreign key (terms_id) references terms(id),
    index idx_user_term_agreements_user_terms_created (user_id, terms_id, created_at, id),
    index idx_user_term_agreements_device_terms_created (device_id, terms_id, created_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table term_context_requirements (
    id bigint auto_increment primary key,
    context varchar(64) not null,
    terms_code varchar(120) not null,
    required boolean not null,
    mutable boolean not null,
    permission_code varchar(120),
    display_order int not null,
    effective_at datetime(6) not null,
    retired_at datetime(6),
    created_at datetime(6) not null default current_timestamp(6),
    index idx_term_context_requirements_active (context, retired_at, display_order, terms_code, effective_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table permission_requirements (
    id bigint auto_increment primary key,
    permission_id bigint not null,
    requirement_type varchar(64) not null,
    requirement_key varchar(120) not null,
    operator varchar(32) not null,
    requirement_value varchar(255),
    failure_code varchar(120) not null,
    effective_at datetime(6) not null,
    retired_at datetime(6),
    created_at datetime(6) not null default current_timestamp(6),
    constraint fk_permission_requirements_permission foreign key (permission_id) references permissions(id),
    index idx_permission_requirements_permission_active (permission_id, effective_at, retired_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table notification_preferences (
    id bigint auto_increment primary key,
    user_id bigint,
    device_id varchar(191),
    preference_key varchar(120) not null,
    enabled boolean not null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    constraint uq_notification_preferences_user_key unique (user_id, preference_key),
    constraint uq_notification_preferences_device_key unique (device_id, preference_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_membership_tiers (
    tier_code varchar(32) primary key,
    monthly_question_limit int not null,
    description varchar(255) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_memberships (
    id bigint auto_increment primary key,
    user_id bigint not null,
    tier varchar(32) not null,
    status varchar(32) not null,
    started_at datetime(6) not null,
    expires_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_user_memberships_user foreign key (user_id) references users(id) on delete cascade,
    index idx_user_memberships_user_status (user_id, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_monthly_question_usage (
    id bigint auto_increment primary key,
    user_id bigint not null,
    usage_month varchar(7) not null,
    system_question_count int not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_user_monthly_question_usage_user_month unique (user_id, usage_month),
    constraint fk_user_monthly_question_usage_user foreign key (user_id) references users(id) on delete cascade,
    index idx_user_monthly_question_usage_user (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_stats (
    id bigint auto_increment primary key,
    user_id bigint not null,
    stat_date date not null,
    topic_key varchar(255) not null,
    topic varchar(255) not null,
    difficulty_level int not null,
    response_count int not null,
    score_count int not null,
    score_sum int not null,
    best_score int not null,
    correct_count int not null,
    latest_at datetime(6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_user_stats_day_topic_level unique (user_id, stat_date, topic_key, difficulty_level),
    index idx_user_stats_user_date (user_id, stat_date),
    index idx_user_stats_user_topic (user_id, topic_key)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_stats_dirty_keys (
    id bigint auto_increment primary key,
    user_id bigint not null,
    stat_date date not null,
    topic_key varchar(255) not null,
    difficulty_level int not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_user_stats_dirty_key unique (user_id, stat_date, topic_key, difficulty_level),
    index idx_user_stats_dirty_updated (updated_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table admin_daily_metrics (
    id bigint auto_increment primary key,
    metric_date date not null,
    metric_key varchar(80) not null,
    dimension varchar(255) not null default '',
    value double not null,
    sample_count bigint not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_admin_daily_metrics_day_key_dimension unique (metric_date, metric_key, dimension),
    index idx_admin_daily_metrics_key_date (metric_key, metric_date),
    index idx_admin_daily_metrics_date (metric_date)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table scheduled_jobs (
    job_name varchar(120) primary key,
    enabled boolean not null default true,
    schedule_type varchar(40) not null,
    schedule_value varchar(120) not null,
    max_retry_count int not null default 3,
    timeout_seconds int not null default 300,
    lock_seconds int not null default 300,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table scheduled_job_runs (
    id bigint auto_increment primary key,
    job_name varchar(120) not null,
    trigger_type varchar(40) not null,
    status varchar(40) not null,
    started_at datetime(6) not null,
    finished_at datetime(6),
    duration_ms bigint,
    summary varchar(500),
    error_message varchar(1000),
    retry_of_run_id bigint,
    created_by varchar(120) not null,
    created_at datetime(6) not null default current_timestamp(6),
    constraint fk_scheduled_job_runs_retry foreign key (retry_of_run_id) references scheduled_job_runs(id),
    index idx_scheduled_job_runs_name_started (job_name, started_at),
    index idx_scheduled_job_runs_status_started (status, started_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table redis_event_outbox (
    id bigint auto_increment primary key,
    event_id varchar(120) not null,
    event_type varchar(64) not null,
    payload_version int not null default 1,
    payload_json text not null,
    status varchar(20) not null default 'PENDING',
    attempts int not null default 0,
    next_attempt_at datetime(6) not null,
    claimed_at datetime(6),
    published_at datetime(6),
    last_error text,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_redis_event_outbox_type_event unique (event_type, event_id),
    constraint chk_redis_event_outbox_status check (status in ('PENDING', 'PROCESSING', 'PUBLISHED')),
    index idx_redis_event_outbox_dispatch (status, next_attempt_at, created_at, id),
    index idx_redis_event_outbox_claim_recovery (status, claimed_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table avatar_categories (
    category_key varchar(64) primary key,
    title_ko varchar(120) not null,
    title_en varchar(120) not null,
    slot varchar(64) not null,
    required boolean not null default false,
    single_select boolean not null default true,
    z_index int not null default 0,
    sort_order int not null default 0,
    active boolean not null default true,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table avatar_items (
    item_key varchar(96) primary key,
    category_key varchar(64) not null,
    slot varchar(64) not null,
    display_name_ko varchar(120) not null,
    display_name_en varchar(120) not null,
    asset_name varchar(160) not null,
    color_hex varchar(16) not null default '#8B5CF6',
    default_grant boolean not null default false,
    compatible_bases text not null,
    z_index int not null default 0,
    sort_order int not null default 0,
    active boolean not null default true,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    constraint fk_avatar_items_category foreign key (category_key) references avatar_categories(category_key),
    index idx_avatar_items_category (category_key, sort_order),
    index idx_avatar_items_slot (slot)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table user_avatar_items (
    id bigint auto_increment primary key,
    user_id bigint not null,
    item_key varchar(96) not null,
    granted_source varchar(64) not null default 'SYSTEM',
    created_at datetime(6) not null default current_timestamp(6),
    constraint uq_user_avatar_items_user_item unique (user_id, item_key),
    constraint fk_user_avatar_items_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_user_avatar_items_item foreign key (item_key) references avatar_items(item_key),
    index idx_user_avatar_items_user (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
