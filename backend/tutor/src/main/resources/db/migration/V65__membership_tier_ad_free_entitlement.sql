alter table user_membership_tiers
    add column if not exists ad_free boolean not null default false;

update user_membership_tiers
set ad_free = case when tier_code in ('TIER2', 'TIER3') then true else false end,
    updated_at = now();
