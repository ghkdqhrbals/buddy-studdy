create table billing_accounts (
    id bigint auto_increment primary key,
    user_id bigint null,
    app_account_token char(36) not null,
    status varchar(32) not null default 'ACTIVE',
    anonymized_subject_hash char(64) null,
    anonymized_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_billing_accounts_user unique (user_id),
    constraint uq_billing_accounts_token unique (app_account_token),
    constraint fk_billing_accounts_user foreign key (user_id) references users(id),
    constraint chk_billing_accounts_status check (status in ('ACTIVE', 'ANONYMIZED')),
    index idx_billing_accounts_status (status, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Stable one-to-one ownership of a RevenueCat/Apple appAccountToken; retained and anonymized after account deletion';

alter table billing_accounts
    modify column app_account_token char(36) not null comment 'Stable lowercase UUID used as RevenueCat App User ID and StoreKit appAccountToken',
    modify column status varchar(32) not null default 'ACTIVE' comment 'Ownership state. Values: ACTIVE, ANONYMIZED',
    modify column anonymized_subject_hash char(64) null comment 'One-way subject reference retained to prevent a deleted purchase chain being restored to another account';

alter table invoices
    drop foreign key fk_invoices_user,
    modify column user_id bigint null comment 'BuddyStudy owner while active; NULL after legally retained billing data is anonymized';
alter table invoices
    add constraint fk_invoices_user foreign key (user_id) references users(id);

alter table payments
    drop foreign key fk_payments_user,
    modify column user_id bigint null comment 'BuddyStudy owner while active; NULL after legally retained billing data is anonymized';
alter table payments
    add constraint fk_payments_user foreign key (user_id) references users(id);

alter table billing_actions
    drop foreign key fk_billing_actions_user,
    modify column user_id bigint null comment 'Requesting BuddyStudy owner while active; NULL after account anonymization';
alter table billing_actions
    add constraint fk_billing_actions_user foreign key (user_id) references users(id);

insert into billing_accounts (user_id, app_account_token, status, created_at, updated_at)
select user_id, app_account_token, 'ACTIVE', created_at, updated_at
from apple_billing_accounts
on duplicate key update updated_at = greatest(billing_accounts.updated_at, values(updated_at));

create table subscription_events (
    id bigint auto_increment primary key,
    provider_event_id varchar(191) not null,
    provider varchar(32) not null,
    event_type varchar(64) not null,
    store varchar(32) null,
    provider_reason varchar(64) null,
    price_milliunits bigint null,
    currency char(3) null,
    user_id bigint null,
    billing_account_id bigint null,
    original_transaction_id varchar(191) null,
    transaction_id varchar(191) null,
    product_id varchar(191) null,
    pending_product_id varchar(191) null,
    environment varchar(32) null,
    purchased_at datetime(6) null,
    expires_at datetime(6) null,
    access_status varchar(32) null,
    renewal_status varchar(32) null,
    processing_status varchar(32) not null default 'PENDING',
    attempt_count int not null default 0,
    max_attempts int not null default 3,
    next_attempt_at datetime(6) not null,
    payload_sha256 char(64) not null,
    last_error text null,
    occurred_at datetime(6) not null,
    processed_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_subscription_events_provider_event unique (provider, provider_event_id),
    constraint uq_subscription_events_provider_transaction_type unique (provider, transaction_id, event_type),
    constraint fk_subscription_events_user foreign key (user_id) references users(id),
    constraint fk_subscription_events_account foreign key (billing_account_id) references billing_accounts(id),
    constraint chk_subscription_events_provider check (provider in ('APPLE', 'REVENUECAT')),
    constraint chk_subscription_events_processing check (processing_status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'IGNORED')),
    constraint chk_subscription_events_access check (access_status is null or access_status in ('PENDING', 'ACTIVE', 'GRACE_PERIOD', 'EXPIRED', 'REVOKED', 'TRANSFERRED', 'UNKNOWN')),
    constraint chk_subscription_events_renewal check (renewal_status is null or renewal_status in ('WILL_RENEW', 'CANCELED', 'BILLING_RETRY', 'NOT_APPLICABLE', 'UNKNOWN')),
    constraint chk_subscription_events_attempts check (attempt_count >= 0 and max_attempts = 3),
    index idx_subscription_events_work (processing_status, next_attempt_at, id),
    index idx_subscription_events_user_time (user_id, occurred_at desc, id desc),
    index idx_subscription_events_original_transaction (provider, original_transaction_id, occurred_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Append-only, idempotent subscription event source; payload content is represented only by a SHA-256 digest';

alter table subscription_events
    modify column event_type varchar(64) not null comment 'Provider or internal event, including SUBSCRIPTION_SNAPSHOT_RECONCILED',
    modify column store varchar(32) null comment 'Verified provider store. Values include APP_STORE and TEST_STORE',
    modify column provider_reason varchar(64) null comment 'Provider cancellation or expiration reason; never interpreted as refund proof by itself',
    modify column price_milliunits bigint null comment 'Verified provider price in thousandths of the currency unit',
    modify column currency char(3) null comment 'ISO 4217 currency for the verified charge',
    modify column processing_status varchar(32) not null default 'PENDING' comment 'Projector state. Values: PENDING, PROCESSING, COMPLETED, FAILED, IGNORED',
    modify column payload_sha256 char(64) not null comment 'Digest of the verified provider payload; credentials and raw JWS are not stored';

insert into subscription_events (
    provider_event_id, provider, event_type, store, provider_reason, price_milliunits, currency,
    user_id, billing_account_id, original_transaction_id, transaction_id, product_id,
    environment, purchased_at, expires_at, access_status, renewal_status,
    processing_status, attempt_count, max_attempts, next_attempt_at, payload_sha256,
    occurred_at, processed_at, created_at, updated_at
)
select concat('migration:payment:', p.id), 'APPLE', 'MIGRATION_PAYMENT_BACKFILLED', 'APP_STORE', null,
       p.price_milliunits, p.currency, p.user_id, ba.id, p.provider_original_transaction_id,
       p.provider_transaction_id, p.product_id, p.environment, p.purchase_at, p.expires_at,
       case
           when p.status in ('REFUNDED', 'REVOKED') then 'REVOKED'
           when p.expires_at is null or p.expires_at > utc_timestamp(6) then 'ACTIVE'
           else 'EXPIRED'
       end,
       'UNKNOWN', 'COMPLETED', 0, 3, utc_timestamp(6), p.signed_payload_sha256,
       p.purchase_at, utc_timestamp(6), p.created_at, utc_timestamp(6)
from payments p
left join billing_accounts ba on ba.user_id = p.user_id
where p.product_type = 'AUTO_RENEWABLE_SUBSCRIPTION'
on duplicate key update provider_event_id = values(provider_event_id);

create table subscriptions (
    id bigint auto_increment primary key,
    billing_account_id bigint not null,
    user_id bigint null,
    provider varchar(32) not null,
    original_transaction_id varchar(191) not null,
    latest_transaction_id varchar(191) null,
    product_id varchar(191) null,
    tier_code varchar(64) null,
    access_status varchar(32) not null,
    renewal_status varchar(32) not null,
    started_at datetime(6) null,
    expires_at datetime(6) null,
    pending_product_id varchar(191) null,
    last_provider_event_at datetime(6) null,
    last_reconciled_at datetime(6) null,
    next_reconcile_at datetime(6) null,
    version bigint not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_subscriptions_original_transaction unique (provider, original_transaction_id),
    constraint fk_subscriptions_account foreign key (billing_account_id) references billing_accounts(id),
    constraint fk_subscriptions_user foreign key (user_id) references users(id),
    constraint fk_subscriptions_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    constraint chk_subscriptions_provider check (provider in ('APPLE', 'REVENUECAT')),
    constraint chk_subscriptions_access check (access_status in ('PENDING', 'ACTIVE', 'GRACE_PERIOD', 'EXPIRED', 'REVOKED', 'TRANSFERRED', 'UNKNOWN')),
    constraint chk_subscriptions_renewal check (renewal_status in ('WILL_RENEW', 'CANCELED', 'BILLING_RETRY', 'NOT_APPLICABLE', 'UNKNOWN')),
    index idx_subscriptions_user_access (user_id, access_status, expires_at),
    index idx_subscriptions_reconcile (next_reconcile_at, access_status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Current projection per Apple originalTransactionId; subscription events remain the source of truth';

insert into subscriptions (
    billing_account_id, user_id, provider, original_transaction_id, latest_transaction_id,
    product_id, tier_code, access_status, renewal_status, started_at, expires_at,
    last_provider_event_at, last_reconciled_at, next_reconcile_at, version, created_at, updated_at
)
select ba.id, p.user_id, 'APPLE', p.provider_original_transaction_id, p.provider_transaction_id,
       p.product_id, coalesce(um.tier, mtp.tier_code, 'TIER1'),
       case
           when um.status = 'ACTIVE'
                and um.started_at <= utc_timestamp(6)
                and (um.expires_at is null or um.expires_at > utc_timestamp(6)) then 'ACTIVE'
           when p.status in ('REFUNDED', 'REVOKED') then 'REVOKED'
           else 'EXPIRED'
       end,
       'UNKNOWN',
       (select min(first_payment.purchase_at)
        from payments first_payment
        where first_payment.provider = p.provider
          and first_payment.provider_original_transaction_id = p.provider_original_transaction_id),
       coalesce(um.expires_at, p.expires_at), p.verified_at, null, utc_timestamp(6), 0,
       p.created_at, utc_timestamp(6)
from payments p
join (
    select provider, provider_original_transaction_id, max(id) latest_payment_id
    from payments
    where product_type = 'AUTO_RENEWABLE_SUBSCRIPTION'
    group by provider, provider_original_transaction_id
) latest on latest.latest_payment_id = p.id
join billing_accounts ba on ba.user_id = p.user_id
left join user_memberships um on um.original_transaction_id = p.provider_original_transaction_id
left join membership_tier_products mtp
       on mtp.provider = 'APPLE' and mtp.product_id = p.product_id
on duplicate key update
    latest_transaction_id = values(latest_transaction_id),
    product_id = values(product_id),
    tier_code = values(tier_code),
    access_status = values(access_status),
    renewal_status = values(renewal_status),
    started_at = values(started_at),
    expires_at = values(expires_at),
    next_reconcile_at = values(next_reconcile_at),
    updated_at = values(updated_at);

create table user_entitlement_projection (
    user_id bigint primary key,
    subscription_id bigint null,
    tier_code varchar(64) not null,
    source varchar(32) not null,
    access_status varchar(32) not null,
    renewal_status varchar(32) not null,
    product_id varchar(191) null,
    started_at datetime(6) null,
    expires_at datetime(6) null,
    will_renew boolean not null default false,
    pending_product_id varchar(191) null,
    projected_at datetime(6) not null,
    version bigint not null default 0,
    constraint fk_user_entitlement_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_user_entitlement_subscription foreign key (subscription_id) references subscriptions(id),
    constraint fk_user_entitlement_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    constraint chk_user_entitlement_source check (source in ('FREE', 'APP_STORE')),
    constraint chk_user_entitlement_access check (access_status in ('PENDING', 'ACTIVE', 'GRACE_PERIOD', 'EXPIRED', 'REVOKED', 'TRANSFERRED', 'UNKNOWN')),
    constraint chk_user_entitlement_renewal check (renewal_status in ('WILL_RENEW', 'CANCELED', 'BILLING_RETRY', 'NOT_APPLICABLE', 'UNKNOWN')),
    index idx_user_entitlement_tier (tier_code, access_status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Single backend authority for the effective user tier; duplicate active subscriptions select the highest tier without adding limits';

insert into user_entitlement_projection (
    user_id, subscription_id, tier_code, source, access_status, renewal_status, product_id,
    started_at, expires_at, will_renew, pending_product_id, projected_at, version
)
select u.id, best.id, coalesce(best.tier_code, 'TIER1'),
       case when best.id is null then 'FREE' else 'APP_STORE' end,
       case when best.id is null then 'ACTIVE' else best.access_status end,
       case when best.id is null then 'NOT_APPLICABLE' else best.renewal_status end,
       best.product_id, best.started_at, best.expires_at,
       case when best.renewal_status = 'WILL_RENEW' then true else false end,
       best.pending_product_id, utc_timestamp(6), 0
from users u
left join (
    select ranked.*
    from (
        select s.*,
               row_number() over (
                   partition by s.user_id
                   order by t.monthly_question_limit desc, s.expires_at desc, s.id desc
               ) as entitlement_rank
        from subscriptions s
        join user_membership_tiers t on t.tier_code = s.tier_code
        where s.access_status in ('ACTIVE', 'GRACE_PERIOD')
          and (s.expires_at is null or s.expires_at > utc_timestamp(6))
    ) ranked
    where ranked.entitlement_rank = 1
) best on best.user_id = u.id
on duplicate key update
    subscription_id = values(subscription_id),
    tier_code = values(tier_code),
    source = values(source),
    access_status = values(access_status),
    renewal_status = values(renewal_status),
    product_id = values(product_id),
    started_at = values(started_at),
    expires_at = values(expires_at),
    will_renew = values(will_renew),
    pending_product_id = values(pending_product_id),
    projected_at = values(projected_at);

create table quota_accounts (
    user_id bigint primary key,
    anchor_type varchar(32) not null,
    anchor_at datetime(6) not null,
    anchor_day tinyint unsigned not null,
    first_paid_at datetime(6) null,
    policy_version int not null default 2,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_quota_accounts_user foreign key (user_id) references users(id) on delete cascade,
    constraint chk_quota_accounts_anchor_type check (anchor_type in ('ACCOUNT_CREATED', 'FIRST_PAID')),
    constraint chk_quota_accounts_anchor_day check (anchor_day between 1 and 31),
    constraint chk_quota_accounts_policy check (policy_version = 2)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Immutable monthly quota anchor; first_paid_at is assigned once from the earliest verified purchase';

insert into quota_accounts (user_id, anchor_type, anchor_at, anchor_day, first_paid_at, policy_version, created_at, updated_at)
select u.id,
       case when p.first_paid_at is null then 'ACCOUNT_CREATED' else 'FIRST_PAID' end,
       coalesce(p.first_paid_at, u.created_at),
       day(coalesce(p.first_paid_at, u.created_at)),
       p.first_paid_at,
       2, utc_timestamp(6), utc_timestamp(6)
from users u
left join (
    select user_id, min(purchase_at) as first_paid_at
    from payments
    where status in ('VERIFIED', 'SETTLED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_REVERSED')
    group by user_id
) p on p.user_id = u.id;

create table quota_periods (
    id bigint auto_increment primary key,
    user_id bigint not null,
    period_started_at datetime(6) not null,
    period_ends_at datetime(6) not null,
    committed_count int unsigned not null default 0,
    reserved_count int unsigned not null default 0,
    bonus_count int not null default 0,
    policy_version int not null default 2,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_quota_periods_user_start unique (user_id, period_started_at),
    constraint fk_quota_periods_user foreign key (user_id) references users(id) on delete cascade,
    constraint chk_quota_periods_range check (period_ends_at > period_started_at),
    constraint chk_quota_periods_counts check (committed_count >= 0 and reserved_count >= 0),
    index idx_quota_periods_user_end (user_id, period_ends_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Per-user monthly quota projection; rows are created lazily and counters are derived from the append-only ledger';

create table quota_reservations (
    id bigint auto_increment primary key,
    reservation_key varchar(191) not null,
    correlation_id varchar(191) not null,
    user_id bigint not null,
    quota_period_id bigint not null,
    status varchar(32) not null,
    reserved_at datetime(6) not null,
    committed_at datetime(6) null,
    released_at datetime(6) null,
    release_reason varchar(1000) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_quota_reservations_key unique (reservation_key),
    constraint uq_quota_reservations_correlation unique (correlation_id),
    constraint fk_quota_reservations_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_quota_reservations_period foreign key (quota_period_id) references quota_periods(id) on delete cascade,
    constraint chk_quota_reservations_status check (status in ('RESERVED', 'COMMITTED', 'RELEASED')),
    index idx_quota_reservations_period_status (quota_period_id, status),
    index idx_quota_reservations_stale (status, reserved_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Exactly-once question quota reservation keyed by generation correlation ID';

create table quota_ledger (
    id bigint auto_increment primary key,
    ledger_event_id varchar(191) not null,
    user_id bigint not null,
    quota_period_id bigint not null,
    reservation_id bigint null,
    ledger_type varchar(32) not null,
    committed_delta int not null default 0,
    reserved_delta int not null default 0,
    bonus_delta int not null default 0,
    reason varchar(1000) null,
    actor_user_id bigint null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint uq_quota_ledger_event unique (ledger_event_id),
    constraint fk_quota_ledger_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_quota_ledger_period foreign key (quota_period_id) references quota_periods(id) on delete cascade,
    constraint fk_quota_ledger_reservation foreign key (reservation_id) references quota_reservations(id) on delete set null,
    constraint fk_quota_ledger_actor foreign key (actor_user_id) references users(id),
    constraint chk_quota_ledger_type check (ledger_type in ('RESERVE', 'COMMIT', 'RELEASE', 'BONUS_GRANT', 'BONUS_REVOKE', 'MIGRATION_ADJUSTMENT')),
    index idx_quota_ledger_period_time (quota_period_id, occurred_at, id),
    index idx_quota_ledger_user_time (user_id, occurred_at desc, id desc)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Append-only source of truth for quota reservation, usage, bonus, and migration adjustments';

insert into quota_periods (
    user_id, period_started_at, period_ends_at, committed_count, reserved_count, bonus_count,
    policy_version, created_at, updated_at
)
select legacy_usage.user_id, legacy_usage.period_start,
       timestampadd(month, 1, legacy_usage.period_start),
       legacy_usage.system_question_count, 0,
       greatest(
           0,
           coalesce(legacy_usage.current_period_question_limit_override, tier.monthly_question_limit, 30)
               - coalesce(tier.monthly_question_limit, 30)
       ),
       2, legacy_usage.created_at, legacy_usage.updated_at
from user_monthly_question_usage legacy_usage
left join (
    select m.user_id, max(t.monthly_question_limit) monthly_question_limit
    from user_memberships m
    join user_membership_tiers t on t.tier_code = m.tier
    where m.status = 'ACTIVE'
    group by m.user_id
) tier on tier.user_id = legacy_usage.user_id
on duplicate key update
    committed_count = greatest(quota_periods.committed_count, values(committed_count)),
    bonus_count = greatest(quota_periods.bonus_count, values(bonus_count)),
    updated_at = greatest(quota_periods.updated_at, values(updated_at));

insert into quota_ledger (
    ledger_event_id, user_id, quota_period_id, reservation_id, ledger_type,
    committed_delta, reserved_delta, bonus_delta, reason, occurred_at, created_at
)
select concat('migration:v2:', qp.user_id, ':', date_format(qp.period_started_at, '%Y%m%d%H%i%s')),
       qp.user_id, qp.id, null, 'MIGRATION_ADJUSTMENT', qp.committed_count, 0, qp.bonus_count,
       'Migrated legacy monthly question usage without resetting the active period', qp.created_at, qp.created_at
from quota_periods qp
on duplicate key update ledger_event_id = ledger_event_id;

insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'billing-subscription-event-projector', true, 'FIXED_DELAY', '1s', 3, 300, 300
) on duplicate key update
    schedule_type = values(schedule_type), schedule_value = values(schedule_value),
    max_retry_count = 3, timeout_seconds = values(timeout_seconds), lock_seconds = values(lock_seconds);
