alter table user_memberships
    drop check chk_user_memberships_status;

alter table user_memberships
    modify column status varchar(32) not null
        comment 'Membership lifecycle state. Values: ACTIVE, INACTIVE',
    add constraint chk_user_memberships_status
        check (status in ('ACTIVE', 'INACTIVE'));

update subscription_events
set processing_status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = utc_timestamp(6),
    last_error = null,
    updated_at = utc_timestamp(6)
where provider = 'REVENUECAT'
  and event_type = 'EXPIRATION'
  and processing_status = 'FAILED'
  and last_error like '%chk_user_memberships_status%';

update revenuecat_billing_events
set processing_status = 'RECEIVED',
    processed_at = null,
    last_error = null,
    updated_at = utc_timestamp(6)
where event_type = 'EXPIRATION'
  and processing_status = 'FAILED'
  and last_error like '%chk_user_memberships_status%';
