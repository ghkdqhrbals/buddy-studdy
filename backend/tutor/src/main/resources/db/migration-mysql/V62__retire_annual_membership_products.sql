-- Annual products remain mapped for historical renewals, refunds, and ledger reads,
-- but cannot be listed in the catalog or used to open a new checkout.
update membership_tier_products
set enabled = false,
    updated_at = utc_timestamp(6)
where provider = 'APPLE'
  and billing_period = 'P1Y';
