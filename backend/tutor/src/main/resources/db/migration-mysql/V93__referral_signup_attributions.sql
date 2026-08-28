create table referral_signup_attributions (
    id bigint auto_increment primary key,
    inviter_user_id bigint null,
    referred_user_id bigint not null,
    referral_code varchar(24) not null,
    status varchar(32) not null default 'PENDING',
    captured_at datetime(6) not null,
    resolved_at datetime(6) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_referral_signup_attributions_referred unique (referred_user_id),
    constraint chk_referral_signup_attributions_status check (status in ('PENDING', 'REWARDED', 'REJECTED')),
    constraint fk_referral_signup_attributions_inviter
        foreign key (inviter_user_id) references users(id) on delete set null,
    constraint fk_referral_signup_attributions_referred
        foreign key (referred_user_id) references users(id) on delete cascade,
    index idx_referral_signup_attributions_inviter_status (inviter_user_id, status, captured_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='First-touch referral attribution captured during the required-terms signup gate';

insert into referral_signup_attributions (
    inviter_user_id, referred_user_id, referral_code, status,
    captured_at, resolved_at, created_at, updated_at
)
select
    inviter_user_id, referred_user_id, referral_code, 'REWARDED',
    redeemed_at, redeemed_at, created_at, updated_at
from referrals;

alter table referrals
    drop foreign key fk_referrals_inviter,
    drop check chk_referrals_not_self,
    modify column inviter_user_id bigint null;

alter table referrals
    add constraint fk_referrals_inviter
        foreign key (inviter_user_id) references users(id) on delete set null;
