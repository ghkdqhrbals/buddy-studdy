-- Lower the original Pro monthly seed from 300 minutes to 60 minutes.
-- Preserve operator-selected tier values and every per-user override. Existing
-- projections reconcile the new tier base lazily without resetting usage,
-- reservations, or the monthly period, just like a normal tier-limit edit.
update user_membership_tiers
set monthly_voice_seconds_limit = 3600,
    updated_at = utc_timestamp(6)
where tier_code in ('TIER2', 'TIER3')
  and monthly_voice_seconds_limit = 18000;
