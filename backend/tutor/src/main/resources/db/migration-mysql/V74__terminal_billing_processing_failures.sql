alter table billing_revenuecat_event_inbox
    drop check chk_revenuecat_billing_events_status,
    add constraint chk_revenuecat_billing_events_status check (
        processing_status in ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED', 'EXHAUSTED')
    ),
    modify column processing_status varchar(32) not null
        comment 'Receipt state. Values: RECEIVED, PROCESSED, IGNORED, FAILED, EXHAUSTED';

alter table subscription_events
    drop check chk_subscription_events_processing,
    add constraint chk_subscription_events_processing check (
        processing_status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'IGNORED', 'EXHAUSTED')
    );

update subscription_events
set processing_status = 'EXHAUSTED',
    processed_at = coalesce(processed_at, updated_at)
where processing_status = 'FAILED'
  and attempt_count >= max_attempts;

update billing_revenuecat_event_inbox inbox
join subscription_events event
  on event.provider = 'REVENUECAT'
 and event.provider_event_id = inbox.event_id
set inbox.processing_status = 'EXHAUSTED',
    inbox.processed_at = coalesce(inbox.processed_at, event.processed_at, inbox.updated_at),
    inbox.last_error = coalesce(inbox.last_error, event.last_error),
    inbox.updated_at = greatest(inbox.updated_at, event.updated_at)
where event.processing_status = 'EXHAUSTED'
  and inbox.processing_status = 'FAILED';
