alter table user_memberships
    drop check chk_user_memberships_source;

alter table user_memberships
    add constraint chk_user_memberships_source
        check (source in ('SYSTEM', 'ADMIN', 'APPLE', 'REFERRAL'));

alter table user_memberships
    modify column source varchar(32) not null default 'SYSTEM'
        comment 'Membership grant source. Values: SYSTEM, ADMIN, APPLE, REFERRAL';

create index idx_user_memberships_effective
    on user_memberships (user_id, status, started_at, expires_at, tier);

create table referral_codes (
    id bigint auto_increment primary key,
    user_id bigint not null,
    code varchar(24) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_referral_codes_user unique (user_id),
    constraint uq_referral_codes_code unique (code),
    constraint fk_referral_codes_user foreign key (user_id) references users(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Stable referral code owned by a registered BuddyStudy user';

create table referrals (
    id bigint auto_increment primary key,
    inviter_user_id bigint not null,
    referred_user_id bigint not null,
    referral_code varchar(24) not null,
    redeemed_at datetime(6) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_referrals_referred_user unique (referred_user_id),
    constraint chk_referrals_not_self check (inviter_user_id <> referred_user_id),
    constraint fk_referrals_inviter foreign key (inviter_user_id) references users(id) on delete cascade,
    constraint fk_referrals_referred foreign key (referred_user_id) references users(id) on delete cascade,
    index idx_referrals_inviter_redeemed (inviter_user_id, redeemed_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='One-time referral redemption linking inviter and referred accounts';

create table referral_reward_grants (
    id bigint auto_increment primary key,
    referral_id bigint not null,
    beneficiary_user_id bigint not null,
    membership_id bigint not null,
    tier_code varchar(32) not null,
    reward_months int not null,
    starts_at datetime(6) not null,
    ends_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint uq_referral_reward_beneficiary unique (referral_id, beneficiary_user_id),
    constraint chk_referral_reward_months check (reward_months > 0),
    constraint fk_referral_reward_referral foreign key (referral_id) references referrals(id) on delete cascade,
    constraint fk_referral_reward_beneficiary foreign key (beneficiary_user_id) references users(id) on delete cascade,
    constraint fk_referral_reward_membership foreign key (membership_id) references user_memberships(id) on delete cascade,
    constraint fk_referral_reward_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    index idx_referral_reward_user_period (beneficiary_user_id, starts_at, ends_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Auditable Tier 2 month granted to each referral beneficiary';
