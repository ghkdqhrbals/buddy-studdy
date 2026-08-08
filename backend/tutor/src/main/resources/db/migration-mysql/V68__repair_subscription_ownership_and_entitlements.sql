update subscriptions s
join payments p
  on p.provider = 'APPLE'
 and p.provider_original_transaction_id = s.original_transaction_id
left join payments newer
  on newer.provider = p.provider
 and newer.provider_original_transaction_id = p.provider_original_transaction_id
 and (
      newer.purchase_at > p.purchase_at
      or (newer.purchase_at = p.purchase_at and newer.id > p.id)
 )
join billing_accounts ba
  on ba.user_id = p.user_id
 and ba.app_account_token = p.app_account_token
 and ba.status = 'ACTIVE'
left join membership_tier_products mtp
  on mtp.provider = 'APPLE'
 and mtp.product_id = p.product_id
set s.billing_account_id = ba.id,
    s.user_id = p.user_id,
    s.latest_transaction_id = p.provider_transaction_id,
    s.product_id = p.product_id,
    s.tier_code = coalesce(mtp.tier_code, s.tier_code),
    s.expires_at = p.expires_at,
    s.updated_at = utc_timestamp(6),
    s.version = s.version + 1
where s.provider = 'APPLE'
  and p.user_id is not null
  and newer.id is null
  and (
      s.user_id is null
      or s.user_id <> p.user_id
      or s.billing_account_id <> ba.id
      or s.latest_transaction_id <> p.provider_transaction_id
  );

update user_memberships um
join subscriptions s
  on s.provider = 'APPLE'
 and s.original_transaction_id = um.original_transaction_id
set um.user_id = s.user_id,
    um.updated_at = utc_timestamp(6)
where um.source = 'APPLE'
  and s.user_id is not null
  and um.user_id <> s.user_id;

insert into user_entitlement_projection (
    user_id, subscription_id, tier_code, source, access_status, renewal_status,
    product_id, started_at, expires_at, will_renew, pending_product_id, projected_at, version
)
select u.id, null, 'TIER1', 'FREE', 'ACTIVE', 'NOT_APPLICABLE',
       null, null, null, false, null, utc_timestamp(6), 0
from users u
on duplicate key update
    subscription_id = null,
    tier_code = 'TIER1',
    source = 'FREE',
    access_status = 'ACTIVE',
    renewal_status = 'NOT_APPLICABLE',
    product_id = null,
    started_at = null,
    expires_at = null,
    will_renew = false,
    pending_product_id = null,
    projected_at = values(projected_at),
    version = user_entitlement_projection.version + 1;

insert into user_entitlement_projection (
    user_id, subscription_id, tier_code, source, access_status, renewal_status,
    product_id, started_at, expires_at, will_renew, pending_product_id, projected_at, version
)
select ranked.user_id, ranked.id, ranked.tier_code, 'APP_STORE', ranked.access_status, ranked.renewal_status,
       ranked.product_id, ranked.started_at, ranked.expires_at,
       ranked.renewal_status = 'WILL_RENEW', ranked.pending_product_id, utc_timestamp(6), 0
from (
    select s.*,
           row_number() over (
               partition by s.user_id
               order by case s.tier_code when 'TIER3' then 3 when 'TIER2' then 2 else 1 end desc,
                        s.expires_at desc,
                        s.id desc
           ) as entitlement_rank
    from subscriptions s
    where s.user_id is not null
      and s.access_status in ('ACTIVE', 'GRACE_PERIOD')
      and (s.expires_at is null or s.expires_at > utc_timestamp(6))
) ranked
where ranked.entitlement_rank = 1
on duplicate key update
    subscription_id = values(subscription_id),
    tier_code = values(tier_code),
    source = 'APP_STORE',
    access_status = values(access_status),
    renewal_status = values(renewal_status),
    product_id = values(product_id),
    started_at = values(started_at),
    expires_at = values(expires_at),
    will_renew = values(will_renew),
    pending_product_id = values(pending_product_id),
    projected_at = values(projected_at),
    version = user_entitlement_projection.version + 1;
