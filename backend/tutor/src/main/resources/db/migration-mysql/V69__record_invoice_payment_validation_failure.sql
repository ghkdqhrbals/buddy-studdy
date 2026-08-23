alter table invoice_events
    drop check chk_invoice_events_type,
    add constraint chk_invoice_events_type check (event_type in (
        'INVOICE_CREATED', 'PAYMENT_VALIDATION_FAILED', 'PAYMENT_VERIFIED',
        'FULFILLMENT_STARTED', 'FULFILLED',
        'CANCELLATION_REQUESTED', 'CANCELLATION_REVERSED', 'CANCELLED',
        'REFUND_REQUESTED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_DECLINED',
        'REFUND_REVERSED', 'COMPENSATION_REQUIRED', 'FULFILLMENT_FAILED',
        'EXPIRED', 'PAYMENT_REVOKED'
    ));

alter table invoice_events
    modify column event_type varchar(64) not null
        comment 'Invoice event type, including terminal payment validation failures';
