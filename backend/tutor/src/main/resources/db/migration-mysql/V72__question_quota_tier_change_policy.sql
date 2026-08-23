alter table quota_accounts
    drop check chk_quota_accounts_policy;

alter table quota_accounts
    modify column policy_version int not null default 3,
    add constraint chk_quota_accounts_policy check (policy_version >= 2),
    comment = 'Monthly quota anchor; a verified paid tier change starts a new quota month';

alter table quota_periods
    modify column policy_version int not null default 3;

update quota_accounts
set policy_version = 3,
    updated_at = utc_timestamp(6)
where policy_version < 3;

update quota_periods
set policy_version = 3,
    updated_at = utc_timestamp(6)
where policy_version < 3;
