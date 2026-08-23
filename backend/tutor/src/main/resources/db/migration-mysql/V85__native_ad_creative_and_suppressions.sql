alter table native_ad_campaigns
    add column image_url varchar(1024) null after body_ja,
    add column affiliate_disclosure_ko varchar(500) null after image_url,
    add column affiliate_disclosure_en varchar(500) null after affiliate_disclosure_ko,
    add column affiliate_disclosure_ja varchar(500) null after affiliate_disclosure_en;

update native_ad_campaigns
set affiliate_disclosure_ko = '이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.',
    affiliate_disclosure_en = 'This content contains Coupang Partners affiliate links, and we may receive a commission from qualifying purchases.',
    affiliate_disclosure_ja = 'このコンテンツはCoupang Partnersの活動の一環として、購入により一定額の手数料を受け取る場合があります。'
where deep_link like 'https://coupang.com/%'
   or deep_link like 'https://www.coupang.com/%'
   or deep_link like 'https://link.coupang.com/%';

create table native_ad_campaign_suppressions (
    id bigint not null auto_increment,
    campaign_id bigint not null,
    user_id bigint not null,
    created_at datetime(6) not null,
    primary key (id),
    unique key uk_native_ad_campaign_suppressions_user_campaign (user_id, campaign_id),
    key idx_native_ad_campaign_suppressions_campaign (campaign_id),
    constraint fk_native_ad_campaign_suppressions_campaign
        foreign key (campaign_id) references native_ad_campaigns(id) on delete cascade,
    constraint fk_native_ad_campaign_suppressions_user
        foreign key (user_id) references users(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='User-owned permanent not-interested exclusions applied before native-ad ranking';
