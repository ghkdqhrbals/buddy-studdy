create table native_ad_campaigns (
    id bigint not null auto_increment,
    campaign_key varchar(96) not null,
    placement varchar(48) not null,
    audience varchar(24) not null,
    disclosure_ko varchar(32) not null,
    disclosure_en varchar(32) not null,
    disclosure_ja varchar(32) not null,
    title_ko varchar(255) not null,
    title_en varchar(255) not null,
    title_ja varchar(255) not null,
    body_ko varchar(500) null,
    body_en varchar(500) null,
    body_ja varchar(500) null,
    deep_link varchar(512) not null,
    base_priority decimal(8,4) not null,
    authenticated_relevance decimal(8,4) not null,
    anonymous_relevance decimal(8,4) not null,
    daily_selection_cap int not null,
    minimum_seconds_between_selections int not null,
    post_view_cooldown_seconds int not null,
    minimum_feed_item_count int not null,
    earliest_position int not null,
    latest_position int not null,
    active boolean not null,
    starts_at datetime(6) null,
    ends_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    unique key uk_native_ad_campaigns_key (campaign_key),
    key idx_native_ad_campaigns_eligibility (placement, active, starts_at, ends_at),
    constraint chk_native_ad_campaigns_audience
        check (audience in ('ALL', 'AUTHENTICATED', 'ANONYMOUS')),
    constraint chk_native_ad_campaigns_caps
        check (
            daily_selection_cap >= 0
            and minimum_seconds_between_selections >= 0
            and post_view_cooldown_seconds >= 0
            and minimum_feed_item_count >= 1
            and earliest_position >= 0
            and latest_position >= earliest_position
        )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Server-owned native advertisement campaigns and ranking controls';

create table native_ad_selection_history (
    id bigint not null auto_increment,
    selection_id char(36) not null,
    campaign_id bigint not null,
    user_id bigint not null,
    device_id varchar(255) not null,
    placement varchar(48) not null,
    language varchar(8) not null,
    position int not null,
    rank_score decimal(12,4) not null,
    selected_at datetime(6) not null,
    viewed_at datetime(6) null,
    primary key (id),
    unique key uk_native_ad_selection_history_selection_id (selection_id),
    key idx_native_ad_selection_history_user_selected (user_id, selected_at),
    key idx_native_ad_selection_history_campaign_selected (campaign_id, selected_at),
    key idx_native_ad_selection_history_campaign_view (campaign_id, viewed_at),
    constraint fk_native_ad_selection_history_campaign
        foreign key (campaign_id) references native_ad_campaigns(id),
    constraint fk_native_ad_selection_history_user
        foreign key (user_id) references users(id) on delete cascade,
    constraint chk_native_ad_selection_history_position check (position >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Durable server-side native-ad selections and idempotent deep-link view evidence';

insert into native_ad_campaigns (
    campaign_key,
    placement,
    audience,
    disclosure_ko,
    disclosure_en,
    disclosure_ja,
    title_ko,
    title_en,
    title_ja,
    body_ko,
    body_en,
    body_ja,
    deep_link,
    base_priority,
    authenticated_relevance,
    anonymous_relevance,
    daily_selection_cap,
    minimum_seconds_between_selections,
    post_view_cooldown_seconds,
    minimum_feed_item_count,
    earliest_position,
    latest_position,
    active,
    starts_at,
    ends_at,
    created_at,
    updated_at
) values (
    'complimentary-feedback-credit',
    'COMMUNITY_FEED',
    'ALL',
    '(광고)',
    '(Ad)',
    '（広告）',
    '의견을 남겨주시면 무료 크레딧을 드려요!',
    'Share your feedback and receive free credits!',
    'ご意見をいただいた方に無料クレジットをプレゼント！',
    '더 나은 공부 경험을 함께 만들어요',
    'Help us build a better study experience',
    'より良い学習体験を一緒につくりましょう',
    'buddystudy://feedback',
    0.9000,
    1.0000,
    0.7000,
    2,
    21600,
    604800,
    4,
    2,
    7,
    true,
    null,
    null,
    utc_timestamp(6),
    utc_timestamp(6)
);
