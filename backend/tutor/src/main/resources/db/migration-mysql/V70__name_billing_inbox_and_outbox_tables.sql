rename table billing_jobs to billing_fulfillment_outbox,
             apple_billing_notifications to billing_apple_notification_inbox,
             revenuecat_billing_events to billing_revenuecat_event_inbox;

alter table billing_fulfillment_outbox
    comment='Durable fulfillment outbox claimed by the managed billing recovery job';

alter table billing_apple_notification_inbox
    comment='Idempotent inbox for verified App Store Server Notifications V2 receipts';

alter table billing_revenuecat_event_inbox
    comment='Idempotent inbox for verified RevenueCat webhook receipts';

-- Keep rolling deployments compatible while old application tasks drain. These
-- simple MERGE views remain writable in MySQL and can be removed after the
-- deployment rollback window closes.
create algorithm=merge sql security invoker view billing_jobs as
select * from billing_fulfillment_outbox;

create algorithm=merge sql security invoker view apple_billing_notifications as
select * from billing_apple_notification_inbox;

create algorithm=merge sql security invoker view revenuecat_billing_events as
select * from billing_revenuecat_event_inbox;
