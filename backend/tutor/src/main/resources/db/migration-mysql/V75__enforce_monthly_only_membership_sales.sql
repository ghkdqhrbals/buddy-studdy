-- Keep retired annual product mappings so historical Apple/RevenueCat transactions,
-- renewals, restores, and refunds remain reconcilable. They must never be enabled
-- for a new BuddyStudy checkout.
update membership_tier_products
set enabled = false,
    updated_at = utc_timestamp(6)
where provider = 'APPLE'
  and billing_period = 'P1Y';

alter table membership_tier_products
    add constraint chk_membership_tier_products_no_annual_sale check (
        billing_period is null
        or billing_period <> 'P1Y'
        or enabled = false
    ),
    modify column billing_period varchar(32) null
        comment 'ISO-8601 billing period. P1M is sellable; P1Y is retained only for historical reconciliation',
    modify column enabled boolean not null default true
        comment 'New-sale availability. Annual P1Y products must remain disabled';
