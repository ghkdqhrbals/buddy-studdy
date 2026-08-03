create table membership_tier_products (
    id bigint auto_increment primary key,
    tier_code varchar(64) not null,
    provider varchar(32) not null,
    product_id varchar(191) not null,
    product_type varchar(48) not null,
    billing_period varchar(32) null,
    enabled boolean not null default true,
    sort_order int not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_membership_tier_products_provider_product unique (provider, product_id),
    constraint uq_membership_tier_products_tier_provider unique (tier_code, provider),
    constraint fk_membership_tier_products_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    constraint chk_membership_tier_products_provider check (provider in ('APPLE')),
    constraint chk_membership_tier_products_product_type check (
        product_type in ('CONSUMABLE', 'NON_CONSUMABLE', 'AUTO_RENEWABLE_SUBSCRIPTION', 'NON_RENEWING_SUBSCRIPTION')
    ),
    constraint chk_membership_tier_products_billing_period check (
        billing_period is null or billing_period in ('P1M', 'P1Y')
    ),
    constraint chk_membership_tier_products_sort_order check (sort_order >= 0),
    index idx_membership_tier_products_enabled (provider, enabled, sort_order)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Server-owned mapping between membership tiers and App Store products';

alter table membership_tier_products
    modify column tier_code varchar(64) not null
        comment 'Membership tier granted by a verified purchase',
    modify column provider varchar(32) not null
        comment 'Billing provider. Values: APPLE',
    modify column product_id varchar(191) not null
        comment 'Exact product identifier configured in App Store Connect',
    modify column product_type varchar(48) not null
        comment 'Product type. Values: CONSUMABLE, NON_CONSUMABLE, AUTO_RENEWABLE_SUBSCRIPTION, NON_RENEWING_SUBSCRIPTION',
    modify column billing_period varchar(32) null
        comment 'ISO-8601 billing period. Values: P1M, P1Y; NULL for non-subscriptions',
    modify column enabled boolean not null default true
        comment 'Whether the product is advertised and accepted for new purchases';

insert into membership_tier_products (
    tier_code, provider, product_id, product_type, billing_period, enabled, sort_order, created_at, updated_at
) values
    (
        'TIER2', 'APPLE', 'io.github.ghkdqhrbals.StudyMate.tier2.monthly',
        'AUTO_RENEWABLE_SUBSCRIPTION', 'P1M', true, 20, utc_timestamp(6), utc_timestamp(6)
    ),
    (
        'TIER3', 'APPLE', 'io.github.ghkdqhrbals.StudyMate.tier3.monthly',
        'AUTO_RENEWABLE_SUBSCRIPTION', 'P1M', true, 30, utc_timestamp(6), utc_timestamp(6)
    );

create table apple_billing_accounts (
    id bigint auto_increment primary key,
    user_id bigint not null,
    app_account_token char(36) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_apple_billing_accounts_user unique (user_id),
    constraint uq_apple_billing_accounts_token unique (app_account_token),
    constraint fk_apple_billing_accounts_user foreign key (user_id) references users(id) on delete cascade,
    constraint chk_apple_billing_accounts_token check (
        app_account_token regexp '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    )
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Stable Apple appAccountToken assigned to a BuddyStudy user';

alter table apple_billing_accounts
    modify column app_account_token char(36) not null
        comment 'Lowercase UUID sent to StoreKit and returned in signed Apple transactions';

create table invoices (
    id bigint auto_increment primary key,
    invoice_number char(36) not null,
    user_id bigint not null,
    tier_code varchar(64) not null,
    provider varchar(32) not null,
    product_id varchar(191) not null,
    app_account_token char(36) not null,
    currency char(3) null,
    subtotal_milliunits bigint null,
    tax_milliunits bigint null,
    total_milliunits bigint null,
    status varchar(48) not null,
    version bigint not null default 0,
    latest_event_sequence bigint not null default 0,
    paid_at datetime(6) null,
    fulfilled_at datetime(6) null,
    cancelled_at datetime(6) null,
    refunded_at datetime(6) null,
    expires_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_invoices_number unique (invoice_number),
    constraint fk_invoices_user foreign key (user_id) references users(id),
    constraint fk_invoices_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    constraint chk_invoices_provider check (provider in ('APPLE')),
    constraint chk_invoices_status check (status in (
        'PENDING_PAYMENT', 'PAYMENT_VERIFIED', 'FULFILLMENT_PENDING', 'FULFILLED',
        'CANCELLATION_REQUESTED', 'CANCELLED', 'REFUND_REQUESTED', 'REFUND_PENDING',
        'REFUNDED', 'REFUND_DECLINED', 'REFUND_REVERSED', 'COMPENSATION_REQUIRED', 'FAILED', 'EXPIRED'
    )),
    constraint chk_invoices_amounts check (
        (subtotal_milliunits is null or subtotal_milliunits >= 0)
        and (tax_milliunits is null or tax_milliunits >= 0)
        and (total_milliunits is null or total_milliunits >= 0)
    ),
    constraint chk_invoices_version check (version >= 0 and latest_event_sequence >= 0),
    index idx_invoices_user_created (user_id, created_at desc, id desc),
    index idx_invoices_status_updated (status, updated_at),
    index idx_invoices_product (provider, product_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Current-state projection of the event-sourced billing invoice aggregate';

alter table invoices
    modify column invoice_number char(36) not null comment 'Public immutable invoice UUID',
    modify column provider varchar(32) not null comment 'Billing provider. Values: APPLE',
    modify column status varchar(48) not null
        comment 'Invoice projection state. Values: PENDING_PAYMENT, PAYMENT_VERIFIED, FULFILLMENT_PENDING, FULFILLED, CANCELLATION_REQUESTED, CANCELLED, REFUND_REQUESTED, REFUND_PENDING, REFUNDED, REFUND_DECLINED, REFUND_REVERSED, COMPENSATION_REQUIRED, FAILED, EXPIRED',
    modify column version bigint not null default 0
        comment 'Optimistic-lock version, incremented with each invoice event',
    modify column latest_event_sequence bigint not null default 0
        comment 'Last applied per-invoice event sequence';

create table invoice_events (
    id bigint auto_increment primary key,
    invoice_id bigint not null,
    event_id varchar(191) not null,
    sequence_number bigint not null,
    event_type varchar(64) not null,
    source varchar(32) not null,
    from_status varchar(48) null,
    to_status varchar(48) not null,
    correlation_id varchar(191) null,
    causation_id varchar(191) null,
    actor_user_id bigint null,
    reason varchar(1000) null,
    metadata_json json null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint uq_invoice_events_event unique (event_id),
    constraint uq_invoice_events_sequence unique (invoice_id, sequence_number),
    constraint fk_invoice_events_invoice foreign key (invoice_id) references invoices(id),
    constraint chk_invoice_events_type check (event_type in (
        'INVOICE_CREATED', 'PAYMENT_VERIFIED', 'FULFILLMENT_STARTED', 'FULFILLED',
        'CANCELLATION_REQUESTED', 'CANCELLATION_REVERSED', 'CANCELLED', 'REFUND_REQUESTED', 'REFUND_PENDING',
        'REFUNDED', 'REFUND_DECLINED', 'REFUND_REVERSED', 'COMPENSATION_REQUIRED',
        'FULFILLMENT_FAILED', 'EXPIRED', 'PAYMENT_REVOKED'
    )),
    constraint chk_invoice_events_source check (source in ('CLIENT', 'APPLE_NOTIFICATION', 'SYSTEM', 'ADMIN')),
    constraint chk_invoice_events_sequence check (sequence_number > 0),
    index idx_invoice_events_invoice_order (invoice_id, sequence_number),
    index idx_invoice_events_correlation (correlation_id),
    index idx_invoice_events_occurred (occurred_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Append-only source of truth for invoice aggregate state changes';

alter table invoice_events
    modify column event_type varchar(64) not null
        comment 'Invoice event type. Values are constrained by chk_invoice_events_type',
    modify column source varchar(32) not null
        comment 'Event origin. Values: CLIENT, APPLE_NOTIFICATION, SYSTEM, ADMIN',
    modify column metadata_json json null
        comment 'Non-authoritative structured audit metadata without raw payment credentials';

create table payments (
    id bigint auto_increment primary key,
    invoice_id bigint not null,
    user_id bigint not null,
    provider varchar(32) not null,
    provider_transaction_id varchar(191) not null,
    provider_original_transaction_id varchar(191) not null,
    app_transaction_id varchar(191) null,
    web_order_line_item_id varchar(191) null,
    app_account_token char(36) not null,
    product_id varchar(191) not null,
    product_type varchar(48) not null,
    environment varchar(32) not null,
    quantity int not null default 1,
    price_milliunits bigint null,
    currency char(3) null,
    status varchar(40) not null,
    purchase_at datetime(6) not null,
    original_purchase_at datetime(6) null,
    expires_at datetime(6) null,
    revocation_at datetime(6) null,
    revocation_reason int null,
    signed_at datetime(6) not null,
    verified_at datetime(6) not null,
    signed_payload_sha256 char(64) not null,
    version bigint not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_payments_provider_transaction unique (provider, provider_transaction_id),
    constraint uq_payments_invoice unique (invoice_id),
    constraint fk_payments_invoice foreign key (invoice_id) references invoices(id),
    constraint fk_payments_user foreign key (user_id) references users(id),
    constraint chk_payments_provider check (provider in ('APPLE')),
    constraint chk_payments_product_type check (
        product_type in ('CONSUMABLE', 'NON_CONSUMABLE', 'AUTO_RENEWABLE_SUBSCRIPTION', 'NON_RENEWING_SUBSCRIPTION')
    ),
    constraint chk_payments_environment check (environment in ('SANDBOX', 'PRODUCTION', 'XCODE')),
    constraint chk_payments_status check (status in (
        'VERIFIED', 'SETTLED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_DECLINED', 'REFUND_REVERSED', 'REVOKED', 'FAILED'
    )),
    constraint chk_payments_quantity check (quantity > 0),
    constraint chk_payments_price check (price_milliunits is null or price_milliunits >= 0),
    constraint chk_payments_version check (version >= 0),
    index idx_payments_user_created (user_id, created_at desc, id desc),
    index idx_payments_original_transaction (provider, provider_original_transaction_id),
    index idx_payments_status_updated (status, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Immutable Apple transaction identity plus its current provider settlement projection';

alter table payments
    modify column provider varchar(32) not null comment 'Billing provider. Values: APPLE',
    modify column provider_transaction_id varchar(191) not null comment 'Unique Apple transactionId for this charge or renewal',
    modify column provider_original_transaction_id varchar(191) not null comment 'Stable Apple originalTransactionId for the purchase chain',
    modify column status varchar(40) not null
        comment 'Payment projection state. Values: VERIFIED, SETTLED, REFUND_PENDING, REFUNDED, REFUND_DECLINED, REFUND_REVERSED, REVOKED, FAILED',
    modify column signed_payload_sha256 char(64) not null comment 'SHA-256 digest of the verified JWS; raw JWS is not stored here';

create table payments_history (
    id bigint auto_increment primary key,
    payment_id bigint not null,
    invoice_id bigint not null,
    event_id varchar(191) not null,
    event_type varchar(64) not null,
    source varchar(32) not null,
    from_status varchar(40) null,
    to_status varchar(40) not null,
    provider_notification_uuid varchar(191) null,
    reason varchar(1000) null,
    metadata_json json null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint uq_payments_history_event unique (event_id),
    constraint fk_payments_history_payment foreign key (payment_id) references payments(id),
    constraint fk_payments_history_invoice foreign key (invoice_id) references invoices(id),
    constraint chk_payments_history_type check (event_type in (
        'VERIFIED', 'SETTLED', 'REFUND_REQUESTED', 'REFUND_PENDING', 'REFUNDED',
        'REFUND_DECLINED', 'REFUND_REVERSED', 'REVOKED', 'VALIDATION_FAILED'
    )),
    constraint chk_payments_history_source check (source in ('CLIENT', 'APPLE_NOTIFICATION', 'SYSTEM', 'ADMIN')),
    index idx_payments_history_payment (payment_id, occurred_at, id),
    index idx_payments_history_notification (provider_notification_uuid)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Append-only payment verification, settlement, cancellation, and refund audit history';

create table billing_actions (
    id bigint auto_increment primary key,
    action_id char(36) not null,
    idempotency_key varchar(191) not null,
    invoice_id bigint not null,
    payment_id bigint not null,
    user_id bigint not null,
    action_type varchar(32) not null,
    status varchar(32) not null,
    reason varchar(1000) null,
    provider_notification_uuid varchar(191) null,
    requested_at datetime(6) not null,
    completed_at datetime(6) null,
    failed_at datetime(6) null,
    last_error text null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_billing_actions_action unique (action_id),
    constraint uq_billing_actions_user_idempotency unique (user_id, action_type, idempotency_key),
    constraint fk_billing_actions_invoice foreign key (invoice_id) references invoices(id),
    constraint fk_billing_actions_payment foreign key (payment_id) references payments(id),
    constraint fk_billing_actions_user foreign key (user_id) references users(id),
    constraint chk_billing_actions_type check (action_type in ('REFUND', 'CANCELLATION', 'COMPENSATION')),
    constraint chk_billing_actions_status check (status in (
        'REQUIRED', 'REQUESTED', 'AWAITING_APPLE', 'COMPLETED', 'DECLINED', 'FAILED', 'CANCELLED'
    )),
    index idx_billing_actions_invoice (invoice_id, created_at desc),
    index idx_billing_actions_status (status, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Idempotent user, admin, and system cancellation/refund/compensation requests';

alter table billing_actions
    modify column action_type varchar(32) not null
        comment 'Requested operation. Values: REFUND, CANCELLATION, COMPENSATION',
    modify column status varchar(32) not null
        comment 'Action state. Values: REQUIRED, REQUESTED, AWAITING_APPLE, COMPLETED, DECLINED, FAILED, CANCELLED';

create table billing_jobs (
    id bigint auto_increment primary key,
    job_id char(36) not null,
    invoice_id bigint not null,
    payment_id bigint not null,
    job_type varchar(32) not null,
    status varchar(32) not null default 'PENDING',
    attempts int not null default 0,
    max_attempts int not null default 3,
    next_attempt_at datetime(6) not null,
    claimed_at datetime(6) null,
    claim_token char(36) null,
    last_error text null,
    completed_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_billing_jobs_job unique (job_id),
    constraint uq_billing_jobs_invoice_type unique (invoice_id, job_type),
    constraint fk_billing_jobs_invoice foreign key (invoice_id) references invoices(id),
    constraint fk_billing_jobs_payment foreign key (payment_id) references payments(id),
    constraint chk_billing_jobs_type check (job_type in ('FULFILLMENT', 'COMPENSATION')),
    constraint chk_billing_jobs_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    constraint chk_billing_jobs_attempts check (attempts >= 0 and max_attempts between 1 and 10),
    index idx_billing_jobs_due (status, next_attempt_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Durable fulfillment and compensation work that survives request and transaction failure';

alter table billing_jobs
    modify column job_type varchar(32) not null
        comment 'Durable billing job. Values: FULFILLMENT, COMPENSATION',
    modify column status varchar(32) not null default 'PENDING'
        comment 'Job state. Values: PENDING, PROCESSING, COMPLETED, FAILED';

create table apple_billing_notifications (
    id bigint auto_increment primary key,
    notification_uuid varchar(191) not null,
    notification_type varchar(64) not null,
    subtype varchar(64) null,
    environment varchar(32) not null,
    signed_payload_sha256 char(64) not null,
    transaction_id varchar(191) null,
    processing_status varchar(32) not null,
    processed_at datetime(6) null,
    last_error text null,
    received_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_apple_billing_notifications_uuid unique (notification_uuid),
    constraint chk_apple_billing_notifications_environment check (environment in ('SANDBOX', 'PRODUCTION', 'XCODE')),
    constraint chk_apple_billing_notifications_status check (processing_status in ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    index idx_apple_billing_notifications_transaction (transaction_id, received_at),
    index idx_apple_billing_notifications_status (processing_status, received_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Idempotency and audit record for verified App Store Server Notifications V2';

alter table user_memberships
    add column source varchar(32) not null default 'SYSTEM' after status,
    add column source_invoice_id bigint null after source,
    add column original_transaction_id varchar(191) null after source_invoice_id,
    add constraint fk_user_memberships_source_invoice foreign key (source_invoice_id) references invoices(id),
    add constraint chk_user_memberships_source check (source in ('SYSTEM', 'ADMIN', 'APPLE')),
    add constraint uq_user_memberships_original_transaction unique (original_transaction_id),
    add index idx_user_memberships_source_invoice (source_invoice_id);

alter table user_memberships
    modify column source varchar(32) not null default 'SYSTEM'
        comment 'Membership grant source. Values: SYSTEM, ADMIN, APPLE',
    modify column source_invoice_id bigint null
        comment 'Invoice that granted this membership when source is APPLE',
    modify column original_transaction_id varchar(191) null
        comment 'Apple originalTransactionId used to update renewals and revoke refunds';
