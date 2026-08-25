create table native_ad_placement_policies (
    placement varchar(48) primary key,
    enabled boolean not null default false,
    daily_delivery_cap integer not null default 2,
    minimum_seconds_between_deliveries integer not null default 21600,
    minimum_feed_item_count integer not null default 4,
    earliest_position integer not null default 2,
    latest_position integer not null default 7,
    starts_at timestamp with time zone null,
    ends_at timestamp with time zone null,
    updated_at timestamp with time zone not null,
    constraint ck_native_ad_placement_policy_limits check (
        daily_delivery_cap >= 0
        and minimum_seconds_between_deliveries >= 60
        and minimum_feed_item_count >= 4
        and earliest_position >= 2
        and latest_position >= earliest_position
    ),
    constraint ck_native_ad_placement_policy_window check (
        ends_at is null or starts_at is null or ends_at > starts_at
    )
);

insert into native_ad_placement_policies (
    placement, enabled, daily_delivery_cap, minimum_seconds_between_deliveries,
    minimum_feed_item_count, earliest_position, latest_position, starts_at, ends_at, updated_at
) values ('COMMUNITY_FEED', false, 2, 21600, 4, 2, 7, null, null, now());

create table native_ad_slots (
    id bigserial primary key,
    slot_id varchar(36) not null unique,
    user_id bigint not null references users(id) on delete cascade,
    device_id varchar(255) not null,
    placement varchar(48) not null references native_ad_placement_policies(placement),
    language varchar(8) not null,
    position integer not null,
    feed_item_count integer not null,
    delivered_at timestamp with time zone not null,
    ad_mob_impression_at timestamp with time zone null,
    ad_mob_click_at timestamp with time zone null,
    constraint ck_native_ad_slots_safe_position check (
        feed_item_count >= 4 and position >= 2 and position < feed_item_count
    )
);

create index idx_native_ad_slots_placement_delivery on native_ad_slots (placement, delivered_at);
create index idx_native_ad_slots_user_delivery on native_ad_slots (user_id, delivered_at);

create table native_ad_delivery_state (
    user_id bigint not null references users(id) on delete cascade,
    placement varchar(48) not null references native_ad_placement_policies(placement),
    delivery_day date not null,
    daily_count integer not null default 0,
    last_delivered_at timestamp with time zone null,
    updated_at timestamp with time zone not null,
    primary key (user_id, placement),
    constraint ck_native_ad_delivery_state_count check (daily_count >= 0)
);

alter table if exists native_ad_selection_history
    add column if not exists native_ad_slot_id varchar(36) null;

alter table if exists native_ad_selection_history
    add constraint uk_native_ad_selection_history_slot unique (native_ad_slot_id);

alter table if exists native_ad_selection_history
    add constraint fk_native_ad_selection_history_slot
        foreign key (native_ad_slot_id) references native_ad_slots(slot_id) on delete cascade;
