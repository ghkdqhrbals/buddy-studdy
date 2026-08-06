insert ignore into subscription_events (
    provider_event_id,
    provider,
    event_type,
    store,
    provider_reason,
    price_milliunits,
    currency,
    user_id,
    billing_account_id,
    original_transaction_id,
    transaction_id,
    product_id,
    environment,
    purchased_at,
    expires_at,
    access_status,
    renewal_status,
    processing_status,
    attempt_count,
    max_attempts,
    next_attempt_at,
    payload_sha256,
    occurred_at,
    created_at,
    updated_at
)
select revenuecat.event_id,
       'REVENUECAT',
       revenuecat.event_type,
       revenuecat.store,
       revenuecat.expiration_reason,
       payment.price_milliunits,
       payment.currency,
       payment.user_id,
       account.id,
       payment.provider_original_transaction_id,
       revenuecat.transaction_id,
       revenuecat.product_id,
       revenuecat.environment,
       payment.purchase_at,
       payment.expires_at,
       'EXPIRED',
       'NOT_APPLICABLE',
       'PENDING',
       0,
       3,
       utc_timestamp(6),
       revenuecat.signed_payload_sha256,
       revenuecat.event_at,
       revenuecat.received_at,
       utc_timestamp(6)
from revenuecat_billing_events revenuecat
left join subscription_events existing
    on existing.provider = 'REVENUECAT'
   and existing.provider_event_id = revenuecat.event_id
left join payments payment
    on payment.provider_transaction_id = revenuecat.transaction_id
left join billing_accounts account
    on account.user_id = payment.user_id
   and account.status = 'ACTIVE'
where revenuecat.event_type = 'EXPIRATION'
  and revenuecat.processing_status = 'RECEIVED'
  and existing.id is null;
