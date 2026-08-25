alter table user_membership_tiers
    add column ad_free boolean not null default false after monthly_question_limit;

update user_membership_tiers
set ad_free = case when tier_code in ('TIER2', 'TIER3') then true else false end,
    updated_at = utc_timestamp(6);
