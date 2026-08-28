alter table if exists native_ad_placement_policies
    drop constraint if exists ck_native_ad_placement_policy_limits;

alter table if exists native_ad_placement_policies
    add constraint ck_native_ad_placement_policy_limits check (
        daily_delivery_cap >= 0
        and (
            minimum_seconds_between_deliveries = 0
            or minimum_seconds_between_deliveries >= 60
        )
        and minimum_feed_item_count >= 4
        and earliest_position >= 2
        and latest_position >= earliest_position
    );
