alter table subscription_events
    add index idx_subscription_events_processing_lease (
        provider,
        processing_status,
        updated_at,
        id
    );
