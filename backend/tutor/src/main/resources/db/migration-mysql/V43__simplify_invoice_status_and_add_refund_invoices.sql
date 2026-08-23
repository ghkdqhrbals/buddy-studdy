alter table invoices
    drop check chk_invoices_status;

alter table invoices
    add column type varchar(32) not null default 'NORMAL' after invoice_number,
    add column original_invoice_id bigint null after type;

update invoices
set status = case
    when status in ('PENDING_PAYMENT', 'PAYMENT_VERIFIED', 'FULFILLMENT_PENDING',
                    'CANCELLATION_REQUESTED', 'REFUND_REQUESTED', 'REFUND_PENDING') then 'WAITING'
    when status in ('FULFILLED', 'REFUNDED', 'REFUND_DECLINED', 'REFUND_REVERSED', 'EXPIRED') then 'COMPLETED'
    when status = 'CANCELLED' and paid_at is not null then 'COMPLETED'
    else 'FAILED'
end;

update invoice_events
set from_status = case
        when from_status is null then null
        when from_status in ('PENDING_PAYMENT', 'PAYMENT_VERIFIED', 'FULFILLMENT_PENDING',
                             'CANCELLATION_REQUESTED', 'REFUND_REQUESTED', 'REFUND_PENDING') then 'WAITING'
        when from_status in ('FULFILLED', 'REFUNDED', 'REFUND_DECLINED', 'REFUND_REVERSED', 'EXPIRED') then 'COMPLETED'
        else 'FAILED'
    end,
    to_status = case
        when to_status in ('PENDING_PAYMENT', 'PAYMENT_VERIFIED', 'FULFILLMENT_PENDING',
                           'CANCELLATION_REQUESTED', 'REFUND_REQUESTED', 'REFUND_PENDING') then 'WAITING'
        when to_status in ('FULFILLED', 'REFUNDED', 'REFUND_DECLINED', 'REFUND_REVERSED', 'EXPIRED') then 'COMPLETED'
        else 'FAILED'
    end;

alter table invoices
    add constraint fk_invoices_original_invoice
        foreign key (original_invoice_id) references invoices(id),
    add constraint chk_invoices_type check (type in ('NORMAL', 'REFUND')),
    add constraint chk_invoices_status check (status in ('WAITING', 'COMPLETED', 'FAILED')),
    add index idx_invoices_original_invoice (original_invoice_id, created_at desc, id desc);

alter table invoices
    modify column type varchar(32) not null default 'NORMAL'
        comment 'Invoice type. Values: NORMAL (일반), REFUND (환불)',
    modify column original_invoice_id bigint null
        comment 'Original NORMAL invoice referenced by a REFUND invoice',
    modify column status varchar(48) not null
        comment 'Invoice projection status. Values: WAITING, COMPLETED, FAILED';

alter table invoice_events
    modify column from_status varchar(48) null
        comment 'Invoice projection status before the event. Values: WAITING, COMPLETED, FAILED',
    modify column to_status varchar(48) not null
        comment 'Invoice projection status after the event. Values: WAITING, COMPLETED, FAILED';
