create table push_campaigns (
    id varchar(36) primary key,
    title varchar(160) not null,
    body text not null,
    deep_link varchar(1000) not null,
    audience varchar(32) not null,
    user_ids_json text not null,
    status varchar(16) not null,
    created_at datetime(6) not null,
    sent_at datetime(6) null,
    archived_recipient_count bigint not null default 0,
    archived_accepted_count bigint not null default 0,
    archived_failed_count bigint not null default 0,
    archived_open_count bigint not null default 0,
    archived_inbox_open_count bigint not null default 0,
    constraint chk_push_campaign_audience check (audience in ('ALL_REGISTERED', 'SELECTED_USERS')),
    constraint chk_push_campaign_status check (status in ('DRAFT', 'QUEUED')),
    index idx_push_campaign_created (created_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table push_campaign_recipients (
    campaign_id varchar(36) not null,
    user_id bigint not null,
    event_id varchar(191) not null,
    created_at datetime(6) not null,
    enqueued_at datetime(6) null,
    primary key (campaign_id, user_id),
    unique key uq_push_campaign_event (event_id),
    index idx_push_campaign_pending (enqueued_at, created_at, campaign_id, user_id),
    constraint fk_push_campaign_recipient_campaign foreign key (campaign_id) references push_campaigns(id) on delete cascade,
    constraint fk_push_campaign_recipient_user foreign key (user_id) references users(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

alter table app_notifications
    add column push_opened_at datetime(6) null,
    add column inbox_opened_at datetime(6) null;

insert into scheduled_jobs (job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds)
values ('push-campaign-dispatch', true, 'FIXED_DELAY', '5s', 3, 60, 90);
