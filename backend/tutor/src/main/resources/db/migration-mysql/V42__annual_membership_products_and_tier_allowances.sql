alter table membership_tier_products
    drop index uq_membership_tier_products_tier_provider,
    add constraint uq_membership_tier_products_tier_provider_period
        unique (tier_code, provider, billing_period);

update user_membership_tiers
set monthly_question_limit = 300,
    description = '300 system questions per monthly allowance window.',
    updated_at = utc_timestamp(6)
where tier_code = 'TIER2';

update user_membership_tiers
set monthly_question_limit = 1000,
    description = '1,000 system questions per monthly allowance window.',
    updated_at = utc_timestamp(6)
where tier_code = 'TIER3';

insert into membership_tier_products (
    tier_code, provider, product_id, product_type, billing_period,
    enabled, sort_order, created_at, updated_at
) values
    (
        'TIER2', 'APPLE', 'io.github.ghkdqhrbals.StudyMate.tier2.yearly',
        'AUTO_RENEWABLE_SUBSCRIPTION', 'P1Y', true, 21, utc_timestamp(6), utc_timestamp(6)
    ),
    (
        'TIER3', 'APPLE', 'io.github.ghkdqhrbals.StudyMate.tier3.yearly',
        'AUTO_RENEWABLE_SUBSCRIPTION', 'P1Y', true, 31, utc_timestamp(6), utc_timestamp(6)
    );
