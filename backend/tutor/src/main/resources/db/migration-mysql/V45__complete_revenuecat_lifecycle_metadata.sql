alter table revenuecat_billing_events
    add column original_app_user_id varchar(191) null
        comment 'Original RevenueCat App User ID used with aliases to resolve the BuddyStudy appAccountToken'
        after app_user_id,
    add column cancel_reason varchar(64) null
        comment 'RevenueCat CANCELLATION reason such as UNSUBSCRIBE, BILLING_ERROR, or CUSTOMER_SUPPORT'
        after environment,
    add column expiration_reason varchar(64) null
        comment 'RevenueCat EXPIRATION reason such as UNSUBSCRIBE, BILLING_ERROR, or CUSTOMER_SUPPORT'
        after cancel_reason;
