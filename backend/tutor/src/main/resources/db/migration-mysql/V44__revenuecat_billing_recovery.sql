create table revenuecat_billing_events (
    id bigint auto_increment primary key,
    event_id varchar(191) not null,
    event_type varchar(64) not null,
    app_user_id varchar(191) null,
    store varchar(32) null,
    product_id varchar(191) null,
    transaction_id varchar(191) null,
    environment varchar(32) null,
    signed_payload_sha256 char(64) not null,
    processing_status varchar(32) not null,
    event_at datetime(6) not null,
    received_at datetime(6) not null,
    processed_at datetime(6) null,
    last_error text null,
    updated_at datetime(6) not null,
    constraint uq_revenuecat_billing_events_event unique (event_id),
    constraint chk_revenuecat_billing_events_status check (
        processing_status in ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')
    ),
    constraint chk_revenuecat_billing_events_environment check (
        environment is null or environment in ('SANDBOX', 'PRODUCTION')
    ),
    index idx_revenuecat_billing_events_transaction (transaction_id, received_at),
    index idx_revenuecat_billing_events_status (processing_status, received_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Idempotency, audit, and recovery receipts for HMAC-verified RevenueCat webhooks';

alter table revenuecat_billing_events
    modify column event_id varchar(191) not null
        comment 'RevenueCat webhook event ID; retries retain the same ID',
    modify column event_type varchar(64) not null
        comment 'RevenueCat lifecycle type such as INITIAL_PURCHASE, RENEWAL, CANCELLATION, or EXPIRATION',
    modify column app_user_id varchar(191) null
        comment 'RevenueCat App User ID; BuddyStudy uses the lowercase Apple appAccountToken UUID',
    modify column signed_payload_sha256 char(64) not null
        comment 'SHA-256 of the exact HMAC-verified raw webhook body; raw billing payload is not retained',
    modify column processing_status varchar(32) not null
        comment 'Receipt state. Values: RECEIVED, PROCESSED, IGNORED, FAILED';

alter table invoice_events
    drop check chk_invoice_events_source,
    add constraint chk_invoice_events_source check (
        source in ('CLIENT', 'APPLE_NOTIFICATION', 'REVENUECAT_WEBHOOK', 'SYSTEM', 'ADMIN')
    ),
    modify column source varchar(32) not null
        comment 'Event origin. Values: CLIENT, APPLE_NOTIFICATION, REVENUECAT_WEBHOOK, SYSTEM, ADMIN';

alter table payments_history
    drop check chk_payments_history_source,
    add constraint chk_payments_history_source check (
        source in ('CLIENT', 'APPLE_NOTIFICATION', 'REVENUECAT_WEBHOOK', 'SYSTEM', 'ADMIN')
    ),
    modify column source varchar(32) not null
        comment 'Payment event origin. Values: CLIENT, APPLE_NOTIFICATION, REVENUECAT_WEBHOOK, SYSTEM, ADMIN';
