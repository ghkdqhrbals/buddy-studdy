alter table quota_reservations
    add column period_started_at datetime(6) null
        comment 'Quota period captured when this reservation was created; terminal settlement stays in this period'
        after user_id,
    add column period_ends_at datetime(6) null
        comment 'Exclusive UTC end of the quota period captured by this reservation'
        after period_started_at;

update quota_reservations reservation
join quota_periods period on period.id = reservation.quota_period_id
set reservation.period_started_at = period.period_started_at
where reservation.period_started_at is null;

update quota_reservations reservation
join quota_periods period on period.id = reservation.quota_period_id
set reservation.period_ends_at = period.period_ends_at
where reservation.period_ends_at is null;

alter table quota_reservations
    modify column quota_period_id bigint null
        comment 'Legacy quota_periods reference retained temporarily for rollback compatibility',
    modify column period_started_at datetime(6) not null
        comment 'Quota period captured when this reservation was created; terminal settlement stays in this period',
    modify column period_ends_at datetime(6) not null
        comment 'Exclusive UTC end of the quota period captured by this reservation',
    add constraint chk_quota_reservations_period_snapshot check (period_ends_at > period_started_at),
    add index idx_quota_reservations_user_period_status (user_id, period_started_at, status);

create table user_quota (
    user_id bigint primary key
        comment 'BuddyStudy user owning this current quota projection',
    tier_code varchar(32) not null
        comment 'Effective membership tier: TIER1, TIER2, or TIER3',
    anchor_type varchar(32) not null
        comment 'Monthly boundary source: ACCOUNT_CREATED or FIRST_PAID',
    anchor_at datetime(6) not null
        comment 'UTC instant from which drift-free monthly boundaries are calculated',
    anchor_day tinyint unsigned not null
        comment 'Original UTC calendar day of anchor_at, from 1 through 31',
    first_paid_at datetime(6) null
        comment 'Earliest verified paid purchase; assigned once and retained across subscription changes',
    period_started_at datetime(6) not null
        comment 'Inclusive UTC start of the currently materialized monthly quota period',
    period_ends_at datetime(6) not null
        comment 'Exclusive UTC end and reset instant of the currently materialized monthly quota period',
    base_limit int unsigned not null
        comment 'Question allowance supplied by the effective tier for the current period',
    bonus_limit int unsigned not null default 0
        comment 'Current-period-only additional allowance granted by an administrator',
    committed_count int unsigned not null default 0
        comment 'Questions successfully generated and charged to the current period',
    reserved_count int unsigned not null default 0
        comment 'In-flight question generations reserved against the current period',
    remaining_count int generated always as (
        greatest(
            0,
            cast(base_limit as signed) + cast(bonus_limit as signed)
                - cast(committed_count as signed) - cast(reserved_count as signed)
        )
    ) stored
        comment 'Generated available allowance: max(0, base + bonus - committed - reserved)',
    policy_version int not null default 5
        comment 'Quota lifecycle policy version; user_quota current-row model starts at version 5',
    version bigint unsigned not null default 0
        comment 'Monotonic current-row revision used to pair mutations with append-only history',
    created_at datetime(6) not null
        comment 'UTC instant when the current-row projection was first materialized',
    updated_at datetime(6) not null
        comment 'UTC instant of the latest projection mutation or period rollover',
    constraint fk_user_quota_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_user_quota_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    constraint chk_user_quota_anchor_type check (anchor_type in ('ACCOUNT_CREATED', 'FIRST_PAID')),
    constraint chk_user_quota_anchor_day check (anchor_day between 1 and 31 and anchor_day = day(anchor_at)),
    constraint chk_user_quota_anchor_paid check (
        (anchor_type = 'ACCOUNT_CREATED' and first_paid_at is null)
        or (anchor_type = 'FIRST_PAID' and first_paid_at is not null)
    ),
    constraint chk_user_quota_period check (period_ends_at > period_started_at),
    constraint chk_user_quota_policy check (policy_version >= 5),
    index idx_user_quota_period_end (period_ends_at, user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Authoritative mutable current monthly question quota row; every mutation is audited in user_quota_history';

insert into user_quota (
    user_id, tier_code, anchor_type, anchor_at, anchor_day, first_paid_at,
    period_started_at, period_ends_at, base_limit, bonus_limit,
    committed_count, reserved_count, policy_version, version, created_at, updated_at
)
select
    quota_window.user_id,
    coalesce(tier.tier_code, 'TIER1'),
    quota_window.anchor_type,
    quota_window.anchor_at,
    day(quota_window.anchor_at),
    quota_window.first_paid_at,
    quota_window.period_started_at,
    quota_window.period_ends_at,
    coalesce(
        case
            when entitlement.tier_code is null then active_membership.monthly_question_limit_override
            else null
        end,
        tier.monthly_question_limit,
        fallback_tier.monthly_question_limit,
        30
    ),
    greatest(coalesce(period.bonus_count, 0), 0),
    coalesce(period.committed_count, 0),
    coalesce(active_reservations.reserved_count, 0),
    5,
    0,
    coalesce(account.created_at, user_row.created_at, utc_timestamp(6)),
    utc_timestamp(6)
from (
    select
        elapsed.user_id,
        elapsed.anchor_type,
        elapsed.anchor_at,
        elapsed.first_paid_at,
        timestampadd(month, elapsed.elapsed_months, elapsed.anchor_at) as period_started_at,
        timestampadd(month, elapsed.elapsed_months + 1, elapsed.anchor_at) as period_ends_at
    from (
        select
            raw_anchor.user_id,
            raw_anchor.anchor_type,
            raw_anchor.anchor_at,
            raw_anchor.first_paid_at,
            raw_anchor.raw_elapsed_months - if(
                timestampadd(month, raw_anchor.raw_elapsed_months, raw_anchor.anchor_at) > utc_timestamp(6),
                1,
                0
            ) as elapsed_months
        from (
            select
                user_anchor.user_id,
                user_anchor.anchor_type,
                user_anchor.anchor_at,
                user_anchor.first_paid_at,
                period_diff(
                    date_format(utc_timestamp(6), '%Y%m'),
                    date_format(user_anchor.anchor_at, '%Y%m')
                ) as raw_elapsed_months
            from (
                select
                    user_base.id as user_id,
                    coalesce(account_base.anchor_type, 'ACCOUNT_CREATED') as anchor_type,
                    coalesce(account_base.anchor_at, user_base.created_at) as anchor_at,
                    account_base.first_paid_at
                from users user_base
                left join quota_accounts account_base on account_base.user_id = user_base.id
            ) user_anchor
        ) raw_anchor
    ) elapsed
) quota_window
join users user_row on user_row.id = quota_window.user_id
left join quota_accounts account on account.user_id = quota_window.user_id
left join quota_periods period
    on period.user_id = quota_window.user_id
    and period.period_started_at = quota_window.period_started_at
left join (
    select
        reservation.user_id,
        reservation.period_started_at,
        reservation.period_ends_at,
        count(*) as reserved_count
    from quota_reservations reservation
    where reservation.status = 'RESERVED'
    group by reservation.user_id, reservation.period_started_at, reservation.period_ends_at
) active_reservations
    on active_reservations.user_id = quota_window.user_id
    and active_reservations.period_started_at = quota_window.period_started_at
    and active_reservations.period_ends_at = quota_window.period_ends_at
left join user_entitlement_projection entitlement
    on entitlement.user_id = quota_window.user_id
    and (
        entitlement.source = 'FREE'
        or entitlement.access_status = 'GRACE_PERIOD'
        or (
            entitlement.access_status = 'ACTIVE'
            and (entitlement.expires_at is null or entitlement.expires_at > utc_timestamp(6))
        )
    )
left join (
    select ranked.user_id, ranked.tier, ranked.monthly_question_limit_override
    from (
        select
            membership.user_id,
            membership.tier,
            membership.monthly_question_limit_override,
            row_number() over (
                partition by membership.user_id
                order by membership.updated_at desc, membership.id desc
            ) as row_rank
        from user_memberships membership
        where membership.status = 'ACTIVE'
          and membership.started_at <= utc_timestamp(6)
          and (membership.expires_at is null or membership.expires_at > utc_timestamp(6))
    ) ranked
    where ranked.row_rank = 1
) active_membership on active_membership.user_id = quota_window.user_id
left join user_membership_tiers tier
    on tier.tier_code = coalesce(entitlement.tier_code, active_membership.tier, 'TIER1')
left join user_membership_tiers fallback_tier on fallback_tier.tier_code = 'TIER1';

create table user_quota_history (
    id bigint auto_increment primary key
        comment 'Monotonic history record identifier',
    event_id varchar(191) not null
        comment 'Globally idempotent quota mutation identifier',
    user_id bigint not null
        comment 'BuddyStudy user whose quota or reservation changed',
    reservation_id bigint null
        comment 'Related exactly-once generation reservation when the event is reservation-scoped',
    event_type varchar(32) not null
        comment 'QUOTA_CREATED, PERIOD_RESET, ANCHOR_CHANGED, RESERVED, COMMITTED, RELEASED, PLAN_UPGRADED, PLAN_DOWNGRADED, BONUS_GRANTED, BONUS_REVOKED, ADMIN_ADJUSTED, or MIGRATION_ADJUSTMENT',
    affected_period_started_at datetime(6) not null
        comment 'Inclusive UTC start of the period affected by this event',
    affected_period_ends_at datetime(6) not null
        comment 'Exclusive UTC end of the period affected by this event',
    applied_to_current boolean not null default true
        comment 'True when this event mutated user_quota; false when an old-period reservation settled after rollover',
    tier_code_before varchar(32) null
        comment 'Effective tier immediately before the mutation',
    tier_code_after varchar(32) null
        comment 'Effective tier immediately after the mutation',
    base_limit_before int unsigned null
        comment 'Tier allowance immediately before the mutation',
    base_limit_after int unsigned null
        comment 'Tier allowance immediately after the mutation',
    bonus_limit_before int unsigned null
        comment 'Current-period bonus immediately before the mutation',
    bonus_limit_after int unsigned null
        comment 'Current-period bonus immediately after the mutation',
    committed_count_before int unsigned null
        comment 'Committed count immediately before the mutation',
    committed_count_after int unsigned null
        comment 'Committed count immediately after the mutation',
    reserved_count_before int unsigned null
        comment 'Reserved count immediately before the mutation',
    reserved_count_after int unsigned null
        comment 'Reserved count immediately after the mutation',
    committed_delta int not null default 0
        comment 'Signed committed-count change represented by this event',
    reserved_delta int not null default 0
        comment 'Signed reserved-count change represented by this event',
    bonus_delta int not null default 0
        comment 'Signed current-period bonus change represented by this event',
    reason varchar(1000) null
        comment 'Human-readable operational or administrative reason',
    actor_user_id bigint null
        comment 'Administrator or user responsible for the change when applicable',
    quota_version_after bigint unsigned null
        comment 'user_quota.version after a current-row mutation; null for imported or old-period-only events',
    occurred_at datetime(6) not null
        comment 'UTC business time at which the quota mutation occurred',
    created_at datetime(6) not null
        comment 'UTC persistence time of this immutable history row',
    constraint uq_user_quota_history_event unique (event_id),
    constraint uq_user_quota_history_version unique (user_id, quota_version_after),
    constraint fk_user_quota_history_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_user_quota_history_reservation foreign key (reservation_id) references quota_reservations(id) on delete set null,
    constraint fk_user_quota_history_actor foreign key (actor_user_id) references users(id) on delete set null,
    constraint fk_user_quota_history_tier_before foreign key (tier_code_before) references user_membership_tiers(tier_code),
    constraint fk_user_quota_history_tier_after foreign key (tier_code_after) references user_membership_tiers(tier_code),
    constraint chk_user_quota_history_type check (event_type in (
        'QUOTA_CREATED', 'PERIOD_RESET', 'ANCHOR_CHANGED', 'RESERVED', 'COMMITTED', 'RELEASED',
        'PLAN_UPGRADED', 'PLAN_DOWNGRADED',
        'BONUS_GRANTED', 'BONUS_REVOKED', 'ADMIN_ADJUSTED', 'MIGRATION_ADJUSTMENT'
    )),
    constraint chk_user_quota_history_period check (affected_period_ends_at > affected_period_started_at),
    constraint chk_user_quota_history_applied check (applied_to_current in (false, true)),
    index idx_user_quota_history_user_time (user_id, occurred_at desc, id desc),
    index idx_user_quota_history_period_user (affected_period_started_at, user_id),
    index idx_user_quota_history_reservation_time (reservation_id, occurred_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Append-only audit history for every current quota, reservation, plan, bonus, rollover, and migration change';

insert into user_quota_history (
    event_id, user_id, reservation_id, event_type,
    affected_period_started_at, affected_period_ends_at, applied_to_current,
    committed_delta, reserved_delta, bonus_delta,
    reason, actor_user_id, quota_version_after, occurred_at, created_at
)
select
    concat('quota-v5:legacy-ledger:', ledger.id),
    ledger.user_id,
    ledger.reservation_id,
    case ledger.ledger_type
        when 'RESERVE' then 'RESERVED'
        when 'COMMIT' then 'COMMITTED'
        when 'RELEASE' then 'RELEASED'
        when 'BONUS_GRANT' then 'BONUS_GRANTED'
        when 'BONUS_REVOKE' then 'BONUS_REVOKED'
        else 'MIGRATION_ADJUSTMENT'
    end,
    period.period_started_at,
    period.period_ends_at,
    period.period_started_at = quota.period_started_at
        and period.period_ends_at = quota.period_ends_at,
    ledger.committed_delta,
    ledger.reserved_delta,
    ledger.bonus_delta,
    left(concat('Imported legacy quota ledger event ', ledger.ledger_event_id,
                coalesce(concat(': ', ledger.reason), '')), 1000),
    ledger.actor_user_id,
    null,
    ledger.occurred_at,
    ledger.created_at
from quota_ledger ledger
join quota_periods period on period.id = ledger.quota_period_id
join user_quota quota on quota.user_id = ledger.user_id;

insert into user_quota_history (
    event_id, user_id, reservation_id, event_type,
    affected_period_started_at, affected_period_ends_at, applied_to_current,
    tier_code_before, tier_code_after, base_limit_before, base_limit_after,
    bonus_limit_before, bonus_limit_after,
    committed_count_before, committed_count_after,
    reserved_count_before, reserved_count_after,
    committed_delta, reserved_delta, bonus_delta,
    reason, actor_user_id, quota_version_after, occurred_at, created_at
)
select
    concat('quota-v5:migration:', quota.user_id),
    quota.user_id,
    null,
    'MIGRATION_ADJUSTMENT',
    quota.period_started_at,
    quota.period_ends_at,
    true,
    null,
    quota.tier_code,
    null,
    quota.base_limit,
    null,
    quota.bonus_limit,
    null,
    quota.committed_count,
    null,
    quota.reserved_count,
    quota.committed_count - coalesce(legacy.committed_count, 0),
    quota.reserved_count - coalesce(legacy.reserved_count, 0),
    quota.bonus_limit - coalesce(legacy.bonus_limit, 0),
    'Backfilled authoritative current quota and reconciled any legacy ledger drift without resetting usage',
    null,
    quota.version,
    quota.updated_at,
    utc_timestamp(6)
from user_quota quota
left join (
    select
        period.user_id,
        period.period_started_at,
        period.period_ends_at,
        sum(ledger.committed_delta) as committed_count,
        sum(ledger.reserved_delta) as reserved_count,
        sum(ledger.bonus_delta) as bonus_limit
    from quota_periods period
    join quota_ledger ledger on ledger.quota_period_id = period.id
    group by period.user_id, period.period_started_at, period.period_ends_at
) legacy
    on legacy.user_id = quota.user_id
    and legacy.period_started_at = quota.period_started_at
    and legacy.period_ends_at = quota.period_ends_at;

insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'user-quota-rollover', true, 'FIXED_DELAY', '60s', 3, 300, 300
) on duplicate key update
    schedule_type = values(schedule_type),
    schedule_value = values(schedule_value),
    max_retry_count = values(max_retry_count),
    timeout_seconds = values(timeout_seconds),
    lock_seconds = values(lock_seconds);
