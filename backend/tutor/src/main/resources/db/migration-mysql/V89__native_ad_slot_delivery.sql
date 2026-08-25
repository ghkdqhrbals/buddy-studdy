create table native_ad_placement_policies (
    placement varchar(48) not null,
    enabled boolean not null default false,
    daily_delivery_cap int not null default 2,
    minimum_seconds_between_deliveries int not null default 21600,
    minimum_feed_item_count int not null default 4,
    earliest_position int not null default 2,
    latest_position int not null default 7,
    starts_at datetime(6) null,
    ends_at datetime(6) null,
    updated_at datetime(6) not null,
    primary key (placement),
    constraint chk_native_ad_placement_policy_limits check (
        daily_delivery_cap >= 0
        and minimum_seconds_between_deliveries >= 60
        and minimum_feed_item_count >= 4
        and earliest_position >= 2
        and latest_position >= earliest_position
    ),
    constraint chk_native_ad_placement_policy_window check (
        ends_at is null or starts_at is null or ends_at > starts_at
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Server-owned native-ad slot delivery policy; disabled until the approved App Store release is live';

insert into native_ad_placement_policies (
    placement, enabled, daily_delivery_cap, minimum_seconds_between_deliveries,
    minimum_feed_item_count, earliest_position, latest_position, starts_at, ends_at, updated_at
) values (
    'COMMUNITY_FEED', false, 2, 21600, 4, 2, 7, null, null, utc_timestamp(6)
);

create table native_ad_slots (
    id bigint not null auto_increment,
    slot_id char(36) not null,
    user_id bigint not null,
    device_id varchar(255) not null,
    placement varchar(48) not null,
    language varchar(8) not null,
    position int not null,
    feed_item_count int not null,
    delivered_at datetime(6) not null,
    ad_mob_impression_at datetime(6) null,
    ad_mob_click_at datetime(6) null,
    primary key (id),
    unique key uk_native_ad_slots_slot_id (slot_id),
    key idx_native_ad_slots_placement_delivery (placement, delivered_at),
    key idx_native_ad_slots_user_delivery (user_id, delivered_at),
    constraint fk_native_ad_slots_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_native_ad_slots_policy foreign key (placement) references native_ad_placement_policies(placement),
    constraint chk_native_ad_slots_safe_position check (
        feed_item_count >= 4 and position >= 2 and position < feed_item_count
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Native-ad slots actually delivered to eligible public-feed viewers';

create table native_ad_delivery_state (
    user_id bigint not null,
    placement varchar(48) not null,
    delivery_day date not null,
    daily_count int not null default 0,
    last_delivered_at datetime(6) null,
    updated_at datetime(6) not null,
    primary key (user_id, placement),
    constraint fk_native_ad_delivery_state_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_native_ad_delivery_state_policy foreign key (placement) references native_ad_placement_policies(placement),
    constraint chk_native_ad_delivery_state_count check (daily_count >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Locked per-user native-ad delivery counter and minimum-interval state';

alter table native_ad_selection_history
    add column native_ad_slot_id char(36) null after selection_id,
    add unique key uk_native_ad_selection_history_slot (native_ad_slot_id),
    add constraint fk_native_ad_selection_history_slot
        foreign key (native_ad_slot_id) references native_ad_slots(slot_id) on delete cascade;
