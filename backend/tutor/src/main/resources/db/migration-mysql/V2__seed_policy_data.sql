insert into roles (code, name, created_at, updated_at) values
    ('ANONYMOUS_USER', 'Anonymous User', utc_timestamp(6), utc_timestamp(6)),
    ('REGISTERED_USER', 'Registered User', utc_timestamp(6), utc_timestamp(6)),
    ('TESTER', 'Tester', utc_timestamp(6), utc_timestamp(6)),
    ('MODERATOR', 'Moderator', utc_timestamp(6), utc_timestamp(6)),
    ('ADMIN', 'Admin', utc_timestamp(6), utc_timestamp(6));

insert into permissions (code, description, requires_active_account, created_at, updated_at) values
    ('device:register', 'Register device', false, utc_timestamp(6), utc_timestamp(6)),
    ('auth:login', 'Login and issue access token', false, utc_timestamp(6), utc_timestamp(6)),
    ('profile:read', 'Read profile', false, utc_timestamp(6), utc_timestamp(6)),
    ('profile:update', 'Update profile', true, utc_timestamp(6), utc_timestamp(6)),
    ('profile:withdraw', 'Withdraw profile', true, utc_timestamp(6), utc_timestamp(6)),
    ('study:read', 'Read studies', false, utc_timestamp(6), utc_timestamp(6)),
    ('study:create', 'Create studies or questions', true, utc_timestamp(6), utc_timestamp(6)),
    ('study:update', 'Update studies', true, utc_timestamp(6), utc_timestamp(6)),
    ('study:delete', 'Delete studies', true, utc_timestamp(6), utc_timestamp(6)),
    ('record:read', 'Read records', false, utc_timestamp(6), utc_timestamp(6)),
    ('record:update', 'Update records', true, utc_timestamp(6), utc_timestamp(6)),
    ('record:delete', 'Delete records', true, utc_timestamp(6), utc_timestamp(6)),
    ('record:publish', 'Publish records', true, utc_timestamp(6), utc_timestamp(6)),
    ('stats:read', 'Read statistics', false, utc_timestamp(6), utc_timestamp(6)),
    ('public-question:read', 'Read public questions', false, utc_timestamp(6), utc_timestamp(6)),
    ('public-question:like', 'Like public questions', true, utc_timestamp(6), utc_timestamp(6)),
    ('public-question:comment', 'Comment on public questions', true, utc_timestamp(6), utc_timestamp(6)),
    ('public-question:report', 'Report public questions', true, utc_timestamp(6), utc_timestamp(6)),
    ('comment:delete', 'Delete comments', true, utc_timestamp(6), utc_timestamp(6)),
    ('debug:read', 'Read debug logs', false, utc_timestamp(6), utc_timestamp(6)),
    ('test-push:send', 'Send test push', true, utc_timestamp(6), utc_timestamp(6)),
    ('admin:read', 'Read admin resources', false, utc_timestamp(6), utc_timestamp(6)),
    ('admin:write', 'Write admin resources', true, utc_timestamp(6), utc_timestamp(6)),
    ('notification:read', 'Read notifications', false, utc_timestamp(6), utc_timestamp(6)),
    ('notification:delete', 'Delete notifications', true, utc_timestamp(6), utc_timestamp(6)),
    ('notification:receive-info', 'Receive informational notifications', false, utc_timestamp(6), utc_timestamp(6)),
    ('notification:receive-marketing', 'Receive marketing notifications', true, utc_timestamp(6), utc_timestamp(6)),
    ('notification:receive-night-marketing', 'Receive night marketing notifications', true, utc_timestamp(6), utc_timestamp(6)),
    ('data:export', 'Export account data', true, utc_timestamp(6), utc_timestamp(6)),
    ('account:delete', 'Delete account', true, utc_timestamp(6), utc_timestamp(6));

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, utc_timestamp(6), utc_timestamp(6)
from roles r join permissions p on p.code in (
    'device:register', 'auth:login', 'profile:read', 'public-question:read'
) where r.code = 'ANONYMOUS_USER';

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, utc_timestamp(6), utc_timestamp(6)
from roles r join permissions p on p.code not in ('debug:read', 'test-push:send', 'admin:read', 'admin:write')
where r.code = 'REGISTERED_USER';

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, utc_timestamp(6), utc_timestamp(6)
from roles r join permissions p on p.code in ('debug:read', 'test-push:send')
where r.code = 'TESTER';

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, utc_timestamp(6), utc_timestamp(6)
from roles r join permissions p on p.code in ('public-question:read', 'comment:delete', 'admin:read')
where r.code = 'MODERATOR';

insert into role_permissions (role_id, permission_id, created_at, updated_at)
select r.id, p.id, utc_timestamp(6), utc_timestamp(6)
from roles r cross join permissions p where r.code = 'ADMIN';

insert into terms (
    code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable
) values
    (
        'TERMS_OF_SERVICE', '2026-07-07', 'ko', '서비스 이용약관',
        'https://ghkdqhrbals.github.io/buddy-studdy/2026-07-07/terms.html',
        'seed-terms-of-service-2026-07-07-ko', '2026-07-07 00:00:00.000000', null, true, false
    ),
    (
        'PRIVACY_POLICY', '2026-07-07', 'ko', '개인정보 처리방침',
        'https://ghkdqhrbals.github.io/buddy-studdy/2026-07-07/privacy.html',
        'seed-privacy-policy-2026-07-07-ko', '2026-07-07 00:00:00.000000', null, true, false
    ),
    (
        'MARKETING_NOTIFICATION', '2026-07-07', 'ko', '마케팅 정보 수신 동의',
        'https://ghkdqhrbals.github.io/buddy-studdy/2026-07-07/terms.html#marketing-notification',
        'seed-marketing-notification-2026-07-07-ko', '2026-07-07 00:00:00.000000', null, false, true
    );

insert into term_context_requirements (
    context, terms_code, required, mutable, permission_code, display_order, effective_at
) values
    ('SIGNUP', 'TERMS_OF_SERVICE', true, false, 'study:create', 10, '2026-07-07 00:00:00.000000'),
    ('SIGNUP', 'PRIVACY_POLICY', true, false, null, 20, '2026-07-07 00:00:00.000000'),
    ('SIGNUP', 'MARKETING_NOTIFICATION', false, true, 'notification:receive-marketing', 30, '2026-07-07 00:00:00.000000');

insert into permission_requirements (
    permission_id, requirement_type, requirement_key, operator, requirement_value, failure_code, effective_at
)
select p.id, v.requirement_type, v.requirement_key, v.operator, v.requirement_value, v.failure_code,
       '2026-07-07 00:00:00.000000'
from permissions p join (
    select 'study:create' permission_code, 'TERMS_AGREED' requirement_type, 'TERMS_OF_SERVICE' requirement_key,
           'LATEST' operator, cast(null as char) requirement_value, 'TERMS_AGREEMENT_REQUIRED' failure_code
    union all select 'study:create', 'USER_STATUS', 'status', 'EQ', 'ACTIVE', 'USER_INACTIVE'
    union all select 'study:create', 'QUOTA_AVAILABLE', 'monthly_question', 'GTE', '1', 'QUOTA_EXCEEDED'
    union all select 'notification:receive-info', 'PREFERENCE_ENABLED', 'question_notification', 'EQ', 'true', 'NOTIFICATION_PREFERENCE_DISABLED'
    union all select 'notification:receive-info', 'DEVICE_REGISTERED', 'apns_token', 'EXISTS', null, 'DEVICE_NOT_REGISTERED'
    union all select 'notification:receive-marketing', 'TERMS_AGREED', 'MARKETING_NOTIFICATION', 'LATEST', null, 'TERMS_AGREEMENT_REQUIRED'
    union all select 'notification:receive-marketing', 'PREFERENCE_ENABLED', 'marketing_notification', 'EQ', 'true', 'NOTIFICATION_PREFERENCE_DISABLED'
    union all select 'notification:receive-marketing', 'DEVICE_REGISTERED', 'apns_token', 'EXISTS', null, 'DEVICE_NOT_REGISTERED'
) v on v.permission_code = p.code;

insert into user_membership_tiers (
    tier_code, monthly_question_limit, description, created_at, updated_at
) values
    ('TIER0', 0, 'User-provided API key tier. Disabled while system-key-only generation is active.', utc_timestamp(6), utc_timestamp(6)),
    ('TIER1', 30, 'Free monthly question quota.', utc_timestamp(6), utc_timestamp(6)),
    ('TIER2', 1000, 'Extended monthly question quota.', utc_timestamp(6), utc_timestamp(6)),
    ('TIER3', 3000, 'Maximum monthly question quota.', utc_timestamp(6), utc_timestamp(6));

insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values
    ('question-schedule', true, 'FIXED_DELAY', '30s', 3, 300, 300),
    ('question-push-outbox-dispatch', true, 'FIXED_DELAY', '30s', 3, 300, 300),
    ('user-stats-refresh', true, 'CRON', '0 */5 * * * *', 3, 300, 300),
    ('admin-analytics-recent', true, 'CRON', '0 */5 * * * *', 3, 300, 300),
    ('admin-analytics-correction', true, 'CRON', '0 20 3 * * *', 3, 300, 300);
