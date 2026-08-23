alter table app_update_campaigns
    add column remote_config_status varchar(16) not null default 'PENDING' after status,
    add column remote_config_revision bigint null after remote_config_status,
    add column remote_config_published_at datetime(6) null after remote_config_revision,
    add column remote_config_error varchar(1000) null after remote_config_published_at,
    add index idx_app_update_remote_config (remote_config_status, remote_config_published_at, id);

create table app_control_events (
    id bigint auto_increment primary key,
    event_id varchar(191) not null,
    user_id bigint not null,
    device_id varchar(191) not null,
    event_type varchar(32) not null,
    platform varchar(32) not null,
    distribution_channel varchar(32) not null,
    app_version varchar(64) not null,
    app_build varchar(64) not null,
    policy_id varchar(191) null,
    policy_revision bigint null,
    campaign_id bigint null,
    evaluated_action varchar(64) null,
    occurred_at datetime(6) not null,
    recorded_at datetime(6) not null,
    unique key uk_app_control_event_id (event_id),
    index idx_app_control_events_device (device_id, recorded_at, id),
    index idx_app_control_events_user (user_id, recorded_at, id),
    index idx_app_control_events_campaign (campaign_id, event_type, recorded_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table app_control_maintenance_windows (
    id bigint auto_increment primary key,
    starts_at datetime(6) not null,
    ends_at datetime(6) null,
    title_ko varchar(255) not null,
    title_en varchar(255) not null,
    title_ja varchar(255) not null,
    message_ko text not null,
    message_en text not null,
    message_ja text not null,
    status varchar(16) not null,
    created_by varchar(191) not null,
    terminated_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_app_control_maintenance_active (status, starts_at, ends_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
