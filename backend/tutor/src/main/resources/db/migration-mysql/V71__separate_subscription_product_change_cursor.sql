alter table subscriptions
    add column pending_product_event_at datetime(6) null after pending_product_id;

update subscriptions s
set s.pending_product_event_at = (
        select max(se.occurred_at)
        from subscription_events se
        where se.original_transaction_id = s.original_transaction_id
          and se.event_type = 'PRODUCT_CHANGE'
          and se.processing_status = 'COMPLETED'
    )
where exists (
    select 1
    from subscription_events se
    where se.original_transaction_id = s.original_transaction_id
      and se.event_type = 'PRODUCT_CHANGE'
      and se.processing_status = 'COMPLETED'
);

update subscriptions s
set s.last_provider_event_at = coalesce(
    (
        select max(se.occurred_at)
        from subscription_events se
        where se.original_transaction_id = s.original_transaction_id
          and se.event_type <> 'PRODUCT_CHANGE'
          and se.processing_status = 'COMPLETED'
    ),
    s.last_provider_event_at
)
where s.pending_product_event_at is not null;
