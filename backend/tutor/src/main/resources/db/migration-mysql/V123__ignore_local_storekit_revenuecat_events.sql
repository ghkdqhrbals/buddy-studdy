-- RevenueCat represents Xcode StoreKit test transactions as APP_STORE/SANDBOX.
-- Retire only unresolved local-test receipts; preserve completed financial history.
-- LEFT compares the literal prefix, including its underscores, rather than LIKE wildcards.
update subscription_events
set processing_status = 'IGNORED',
    processed_at = utc_timestamp(6),
    updated_at = utc_timestamp(6),
    last_error = 'Xcode StoreKit test transaction is outside RevenueCat billing fulfillment.'
where provider = 'REVENUECAT'
  and store = 'APP_STORE'
  and environment = 'SANDBOX'
  and processing_status in ('PENDING', 'PROCESSING', 'FAILED', 'EXHAUSTED')
  and (
      left(transaction_id, 25) = binary 'StoreKitTest_Transaction_'
      or left(original_transaction_id, 25) = binary 'StoreKitTest_Transaction_'
  );

update billing_revenuecat_event_inbox inbox
set inbox.processing_status = 'IGNORED',
    inbox.processed_at = utc_timestamp(6),
    inbox.updated_at = utc_timestamp(6),
    inbox.last_error = 'Xcode StoreKit test transaction is outside RevenueCat billing fulfillment.'
where inbox.store = 'APP_STORE'
  and inbox.environment = 'SANDBOX'
  and inbox.processing_status in ('RECEIVED', 'FAILED', 'EXHAUSTED')
  and (
      left(inbox.transaction_id, 25) = binary 'StoreKitTest_Transaction_'
      or exists (
          select 1 from subscription_events event
          where event.provider = 'REVENUECAT'
            and event.provider_event_id = inbox.event_id
            and event.store = 'APP_STORE'
            and event.environment = 'SANDBOX'
            and left(event.original_transaction_id, 25) = binary 'StoreKitTest_Transaction_'
      )
  );
