alter table devices
    add column app_version varchar(64) null after timezone,
    add column app_build varchar(64) null after app_version,
    add column app_version_seen_at datetime(6) null after app_build,
    add index idx_devices_user_app_version_seen (user_id, app_version_seen_at, id);

create table app_update_campaigns (
    id bigint auto_increment primary key,
    platform varchar(32) not null,
    target_version varchar(64) not null,
    target_build varchar(64) not null,
    update_mode varchar(16) not null,
    title_ko varchar(255) not null,
    title_en varchar(255) not null,
    title_ja varchar(255) not null,
    message_ko text not null,
    message_en text not null,
    message_ja text not null,
    app_store_url varchar(1024) not null,
    status varchar(16) not null,
    created_by varchar(191) not null,
    activated_at datetime(6) not null,
    ended_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    index idx_app_update_campaigns_active (platform, status, activated_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table app_update_user_states (
    campaign_id bigint not null,
    user_id bigint not null,
    device_id varchar(191) not null,
    first_version varchar(64) not null,
    first_build varchar(64) not null,
    current_version varchar(64) not null,
    current_build varchar(64) not null,
    first_checked_at datetime(6) not null,
    last_checked_at datetime(6) not null,
    prompted_at datetime(6) null,
    dismissed_at datetime(6) null,
    app_store_opened_at datetime(6) null,
    converted_at datetime(6) null,
    primary key (campaign_id, user_id),
    index idx_app_update_states_campaign_conversion (campaign_id, converted_at, prompted_at),
    index idx_app_update_states_user_checked (user_id, last_checked_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
