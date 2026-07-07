create table if not exists terms (
    id bigserial primary key,
    code varchar(120) not null,
    version varchar(32) not null,
    locale varchar(16) not null,
    title varchar(255) not null,
    url varchar(1000) not null,
    content_hash varchar(128) not null,
    effective_at timestamptz not null,
    retired_at timestamptz,
    created_at timestamptz not null default now(),
    constraint uq_terms_code_version_locale unique (code, version, locale)
);

create index if not exists idx_terms_active
    on terms (code, effective_at desc, id desc)
    where retired_at is null;

create table if not exists user_term_agreements (
    id bigserial primary key,
    user_id bigint,
    device_id varchar(191),
    terms_id bigint not null references terms(id) on delete restrict,
    action varchar(32) not null,
    source varchar(32) not null,
    ip_address varchar(64),
    user_agent varchar(1000),
    app_version varchar(64),
    created_at timestamptz not null default now(),
    constraint chk_user_term_agreements_subject check (user_id is not null or device_id is not null),
    constraint chk_user_term_agreements_action check (action in ('AGREED', 'WITHDRAWN')),
    constraint chk_user_term_agreements_source check (source in ('SIGNUP', 'SETTINGS', 'REQUIRED_GATE', 'MIGRATION'))
);

create index if not exists idx_user_term_agreements_user_terms_created
    on user_term_agreements (user_id, terms_id, created_at desc, id desc);

create index if not exists idx_user_term_agreements_device_terms_created
    on user_term_agreements (device_id, terms_id, created_at desc, id desc);

create table if not exists notification_preferences (
    id bigserial primary key,
    user_id bigint,
    device_id varchar(191),
    preference_key varchar(120) not null,
    enabled boolean not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_notification_preferences_subject check (user_id is not null or device_id is not null)
);

create unique index if not exists uq_notification_preferences_user_key
    on notification_preferences (user_id, preference_key)
    where user_id is not null;

create unique index if not exists uq_notification_preferences_device_key
    on notification_preferences (device_id, preference_key)
    where user_id is null;

create table if not exists permission_requirements (
    id bigserial primary key,
    permission_id bigint not null references permissions(id) on delete cascade,
    requirement_type varchar(64) not null,
    requirement_key varchar(120) not null,
    operator varchar(32) not null,
    requirement_value varchar(255),
    failure_code varchar(120) not null,
    effective_at timestamptz not null,
    retired_at timestamptz,
    created_at timestamptz not null default now()
);

create index if not exists idx_permission_requirements_permission_active
    on permission_requirements (permission_id, effective_at, retired_at);

insert into permissions (code, description, requires_active_account, created_at, updated_at)
select v.code, v.description, v.requires_active_account, now(), now()
from (
    select 'notification:receive-info' as code, 'Receive informational notifications' as description, false as requires_active_account
    union all select 'notification:receive-marketing', 'Receive marketing notifications', true
    union all select 'notification:receive-night-marketing', 'Receive night marketing notifications', true
    union all select 'data:export', 'Export account data', true
    union all select 'account:delete', 'Delete account', true
) v
where not exists (select 1 from permissions p where p.code = v.code);

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, now(), now()
from roles r
join permissions p on p.code in (
    'notification:receive-info',
    'notification:receive-marketing',
    'notification:receive-night-marketing',
    'data:export',
    'account:delete'
)
where r.code = 'REGISTERED_USER'
  and not exists (select 1 from role_permissions rp where rp.role_id = r.id and rp.permission_id = p.id);

insert into terms (code, version, locale, title, url, content_hash, effective_at)
select v.code, v.version, v.locale, v.title, v.url, v.content_hash, v.effective_at
from (
    select 'TERMS_OF_SERVICE' as code, '2026-07-07' as version, 'ko' as locale, '서비스 이용약관' as title,
           'https://ghkdqhrbals.github.io/buddy-studdy/terms.html' as url, 'seed-terms-of-service-2026-07-07-ko' as content_hash, timestamp with time zone '2026-07-07 00:00:00+00' as effective_at
    union all select 'PRIVACY_POLICY', '2026-07-07', 'ko', '개인정보 처리방침',
           'https://ghkdqhrbals.github.io/buddy-studdy/privacy.html', 'seed-privacy-policy-2026-07-07-ko', timestamp with time zone '2026-07-07 00:00:00+00'
    union all select 'INFO_NOTIFICATION', '2026-07-07', 'ko', '정보성 알림 수신 동의',
           'https://ghkdqhrbals.github.io/buddy-studdy/terms.html#info-notification', 'seed-info-notification-2026-07-07-ko', timestamp with time zone '2026-07-07 00:00:00+00'
    union all select 'MARKETING_NOTIFICATION', '2026-07-07', 'ko', '마케팅 알림 수신 동의',
           'https://ghkdqhrbals.github.io/buddy-studdy/terms.html#marketing-notification', 'seed-marketing-notification-2026-07-07-ko', timestamp with time zone '2026-07-07 00:00:00+00'
    union all select 'NIGHT_MARKETING_NOTIFICATION', '2026-07-07', 'ko', '야간 마케팅 알림 수신 동의',
           'https://ghkdqhrbals.github.io/buddy-studdy/terms.html#night-marketing-notification', 'seed-night-marketing-notification-2026-07-07-ko', timestamp with time zone '2026-07-07 00:00:00+00'
) v
where not exists (
    select 1 from terms t where t.code = v.code and t.version = v.version and t.locale = v.locale
);

insert into permission_requirements (
    permission_id,
    requirement_type,
    requirement_key,
    operator,
    requirement_value,
    failure_code,
    effective_at
)
select p.id, v.requirement_type, v.requirement_key, v.operator, v.requirement_value, v.failure_code, timestamp with time zone '2026-07-07 00:00:00+00'
from (
    select 'study:create' as permission_code, 'TERMS_AGREED' as requirement_type, 'TERMS_OF_SERVICE' as requirement_key, 'LATEST' as operator, null as requirement_value, 'TERMS_AGREEMENT_REQUIRED' as failure_code
    union all select 'study:create', 'USER_STATUS', 'status', 'EQ', 'ACTIVE', 'USER_INACTIVE'
    union all select 'study:create', 'QUOTA_AVAILABLE', 'monthly_question', 'GTE', '1', 'QUOTA_EXCEEDED'
    union all select 'stats:read', 'TERMS_AGREED', 'PRIVACY_POLICY', 'LATEST', null, 'TERMS_AGREEMENT_REQUIRED'
    union all select 'notification:receive-info', 'PREFERENCE_ENABLED', 'info_notification', 'EQ', 'true', 'NOTIFICATION_PREFERENCE_DISABLED'
    union all select 'notification:receive-info', 'DEVICE_REGISTERED', 'apns_token', 'EXISTS', null, 'DEVICE_NOT_REGISTERED'
    union all select 'notification:receive-marketing', 'TERMS_AGREED', 'MARKETING_NOTIFICATION', 'LATEST', null, 'TERMS_AGREEMENT_REQUIRED'
    union all select 'notification:receive-marketing', 'PREFERENCE_ENABLED', 'marketing_notification', 'EQ', 'true', 'NOTIFICATION_PREFERENCE_DISABLED'
    union all select 'notification:receive-marketing', 'DEVICE_REGISTERED', 'apns_token', 'EXISTS', null, 'DEVICE_NOT_REGISTERED'
    union all select 'notification:receive-night-marketing', 'TERMS_AGREED', 'NIGHT_MARKETING_NOTIFICATION', 'LATEST', null, 'TERMS_AGREEMENT_REQUIRED'
    union all select 'notification:receive-night-marketing', 'PREFERENCE_ENABLED', 'night_marketing_notification', 'EQ', 'true', 'NOTIFICATION_PREFERENCE_DISABLED'
    union all select 'notification:receive-night-marketing', 'DEVICE_REGISTERED', 'apns_token', 'EXISTS', null, 'DEVICE_NOT_REGISTERED'
) v
join permissions p on p.code = v.permission_code
where not exists (
    select 1
      from permission_requirements pr
     where pr.permission_id = p.id
       and pr.requirement_type = v.requirement_type
       and pr.requirement_key = v.requirement_key
       and pr.operator = v.operator
       and coalesce(pr.requirement_value, '') = coalesce(v.requirement_value, '')
       and pr.retired_at is null
);
