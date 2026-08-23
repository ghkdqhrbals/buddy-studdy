alter table native_ad_selection_history
    add column impression_at datetime(6) null after selected_at,
    add index idx_native_ad_selection_campaign_impression (campaign_id, impression_at);
