-- Normalize legacy casing/locale variants before enforcing the enum contracts.
update users
set provider = upper(provider),
    status = upper(status),
    avatar_mode = upper(avatar_mode),
    app_language = case
        when lower(app_language) like 'ja%' then 'ja'
        when lower(app_language) like 'en%' then 'en'
        else 'ko'
    end;

update devices
set platform = lower(platform),
    apns_environment = lower(apns_environment),
    language = case
        when lower(language) like 'ja%' then 'ja'
        when lower(language) like 'en%' then 'en'
        else 'ko'
    end;

update user_memberships set status = upper(status);
update avatar_categories set slot = lower(slot);
update avatar_items set slot = lower(slot);
update user_avatar_items set granted_source = upper(granted_source);
update feedbacks set status = upper(status);
update app_notifications
set type = upper(type),
    thread_type = lower(thread_type)
where thread_type is not null;
update questions
set status = lower(status),
    source = lower(source),
    grading_verdict = upper(grading_verdict),
    grading_status = upper(grading_status);
update question_push_outbox set status = upper(status);
update study_question_jobs set status = upper(status);

-- Entity-backed table descriptions.
alter table users comment = 'Registered, anonymous, and withdrawn BuddyStudy user accounts';
alter table user_memberships comment = 'Current membership assignment and lifecycle state for each user';
alter table user_membership_tiers comment = 'Operator-managed membership tier catalog and quota policy';
alter table user_monthly_question_usage comment = 'Monthly system-question usage counters per user';
alter table devices comment = 'iOS installation, APNs routing, locale, and observed app version';
alter table permissions comment = 'Operator-managed authorization permission catalog';
alter table roles comment = 'Operator-managed authorization role catalog';
alter table role_permissions comment = 'Many-to-many role to permission assignments';
alter table user_devices comment = 'User login sessions bound to iOS installations';
alter table user_roles comment = 'Many-to-many user to role assignments';
alter table avatar_categories comment = 'Avatar builder category and rendering-slot catalog';
alter table avatar_items comment = 'Avatar builder item catalog';
alter table user_avatar_items comment = 'Avatar items granted to individual users';
alter table feedbacks comment = 'User feedback and its admin review lifecycle';
alter table question_comments comment = 'Community comments with independently tracked original language';
alter table question_likes comment = 'User likes on public questions';
alter table reports comment = 'Community moderation reports';
alter table user_blocks comment = 'User-level community visibility blocks';
alter table app_notifications comment = 'In-app notification inbox and APNs delivery state';
alter table user_stats comment = 'Daily topic-and-difficulty statistics read model';
alter table question_embeddings comment = 'Question embeddings used for semantic similarity checks';
alter table questions comment = 'Study questions, user answers, grading results, and lifecycle state';
alter table question_push_outbox comment = 'Durable pending APNs question publications';
alter table question_stats comment = 'Materialized engagement counters for questions';
alter table studies comment = 'User-owned study tree nodes and scheduling settings';
alter table study_question_concepts comment = 'Generated concept hierarchy used for question coverage';
alter table study_question_coverage comment = 'Per-concept and angle question coverage counters';
alter table study_question_jobs comment = 'Durable scheduled question-generation jobs';

-- Enum columns retain VARCHAR storage but expose their exact application contract in MySQL metadata.
alter table users
    modify column provider varchar(32) not null
        comment 'Authentication provider. Values: ANONYMOUS, APPLE, GOOGLE, EMAIL, WITHDRAWN',
    modify column status varchar(32) not null
        comment 'Account lifecycle state. Values: ANONYMOUS, PENDING_TERMS, ACTIVE, WITHDRAWN',
    modify column avatar_mode varchar(32) not null default 'BUILDER'
        comment 'Avatar presentation mode. Values: BUILDER, PHOTO, PIXEL',
    modify column app_language varchar(16) not null default 'ko'
        comment 'Preferred application/content language. Values: ko, en, ja',
    add constraint chk_users_provider
        check (provider in ('ANONYMOUS', 'APPLE', 'GOOGLE', 'EMAIL', 'WITHDRAWN')),
    add constraint chk_users_status
        check (status in ('ANONYMOUS', 'PENDING_TERMS', 'ACTIVE', 'WITHDRAWN')),
    add constraint chk_users_avatar_mode
        check (avatar_mode in ('BUILDER', 'PHOTO', 'PIXEL')),
    add constraint chk_users_app_language
        check (app_language in ('ko', 'en', 'ja'));

alter table user_memberships
    modify column status varchar(32) not null
        comment 'Membership lifecycle state. Values: ACTIVE',
    add constraint chk_user_memberships_status
        check (status in ('ACTIVE'));

alter table devices
    modify column platform varchar(32) not null
        comment 'Client platform. Values: ios',
    modify column apns_environment varchar(32) not null
        comment 'APNs token environment. Values: sandbox, production',
    modify column language varchar(16) not null
        comment 'Device content language. Values: ko, en, ja',
    add constraint chk_devices_platform
        check (platform in ('ios')),
    add constraint chk_devices_apns_environment
        check (apns_environment in ('sandbox', 'production')),
    add constraint chk_devices_language
        check (language in ('ko', 'en', 'ja'));

alter table avatar_categories
    modify column slot varchar(64) not null
        comment 'Avatar rendering slot. Values: base, background, top, bottom, shoes, hat, item',
    add constraint chk_avatar_categories_slot
        check (slot in ('base', 'background', 'top', 'bottom', 'shoes', 'hat', 'item'));

alter table avatar_items
    modify column slot varchar(64) not null
        comment 'Avatar rendering slot. Values: base, background, top, bottom, shoes, hat, item',
    add constraint chk_avatar_items_slot
        check (slot in ('base', 'background', 'top', 'bottom', 'shoes', 'hat', 'item'));

alter table user_avatar_items
    modify column granted_source varchar(64) not null default 'SYSTEM'
        comment 'How the avatar item was granted. Values: SYSTEM',
    add constraint chk_user_avatar_items_granted_source
        check (granted_source in ('SYSTEM'));

alter table feedbacks
    modify column status varchar(32) not null default 'NEW'
        comment 'Admin review lifecycle. Values: NEW, REVIEWED, REPLIED',
    add constraint chk_feedbacks_status
        check (status in ('NEW', 'REVIEWED', 'REPLIED'));

alter table question_comments
    modify column source_language varchar(16) not null
        comment 'Original comment language. Values: ko, en, ja';

alter table app_notifications
    modify column type varchar(64) not null
        comment 'Notification category. Values: ACTIVITY, THREAD_ACTIVITY, STUDY_QUESTION, QUESTION_READY, ADMIN_MESSAGE, MARKETING',
    modify column thread_type varchar(64) null
        comment 'Optional navigation thread. Values: question, study_question, admin_message, comment',
    add constraint chk_app_notifications_type
        check (type in ('ACTIVITY', 'THREAD_ACTIVITY', 'STUDY_QUESTION', 'QUESTION_READY', 'ADMIN_MESSAGE', 'MARKETING')),
    add constraint chk_app_notifications_thread_type
        check (thread_type is null or thread_type in ('question', 'study_question', 'admin_message', 'comment'));

alter table questions
    modify column source_language varchar(16) not null
        comment 'Original question language. Values: ko, en, ja',
    modify column answer_source_language varchar(16) null
        comment 'Original user-answer language. Values: ko, en, ja; NULL until answered',
    modify column ai_response_source_language varchar(16) null
        comment 'Original AI feedback language. Values: ko, en, ja; NULL until graded',
    modify column status varchar(32) not null
        comment 'Question lifecycle state. Values: ungraded, graded, skipped',
    modify column source varchar(64) not null
        comment 'Question creation source. Values: scheduled, manual',
    modify column grading_verdict varchar(32) null
        comment 'AI grading verdict. Values: CORRECT, PARTIALLY_CORRECT, INCORRECT',
    modify column grading_status varchar(40) null
        comment 'Async grading state. Values: QUEUED, ANALYZING_EVIDENCE, CRITIQUING, JUDGING, ADJUDICATING, COMPLETED, FAILED',
    add constraint chk_questions_status
        check (status in ('ungraded', 'graded', 'skipped')),
    add constraint chk_questions_source
        check (source in ('scheduled', 'manual')),
    add constraint chk_questions_grading_verdict
        check (grading_verdict is null or grading_verdict in ('CORRECT', 'PARTIALLY_CORRECT', 'INCORRECT')),
    add constraint chk_questions_grading_status
        check (
            grading_status is null or grading_status in (
                'QUEUED',
                'ANALYZING_EVIDENCE',
                'CRITIQUING',
                'JUDGING',
                'ADJUDICATING',
                'COMPLETED',
                'FAILED'
            )
        );

alter table question_push_outbox
    modify column language varchar(16) not null
        comment 'Notification content language. Values: ko, en, ja',
    modify column status varchar(32) not null
        comment 'Push publication state. Values: PENDING, PROCESSING, PUBLISHED',
    add constraint chk_question_push_outbox_language
        check (language in ('ko', 'en', 'ja')),
    add constraint chk_question_push_outbox_status
        check (status in ('PENDING', 'PROCESSING', 'PUBLISHED'));

alter table study_question_jobs
    modify column status varchar(32) not null
        comment 'Question job lifecycle. Values: SCHEDULED, PROCESSING, COMPLETED, CANCELED, FAILED',
    add constraint chk_study_question_jobs_status
        check (status in ('SCHEDULED', 'PROCESSING', 'COMPLETED', 'CANCELED', 'FAILED'));

-- Localization tables are SQL-backed read models rather than Spring Data entities, but their enum
-- metadata is documented alongside the entity source-language columns for database operators.
alter table question_localizations
    comment = 'Localized question/topic/hint read model',
    modify column target_language varchar(16) not null
        comment 'Requested translation language. Values: ko, en, ja',
    modify column source_language varchar(16) not null
        comment 'Original content language. Values: ko, en, ja',
    modify column status varchar(16) not null default 'READY'
        comment 'Translation lifecycle. Values: PENDING, READY, FAILED';

alter table answer_localizations
    comment = 'Localized user-answer read model',
    modify column target_language varchar(16) not null
        comment 'Requested translation language. Values: ko, en, ja',
    modify column source_language varchar(16) not null
        comment 'Original content language. Values: ko, en, ja',
    modify column status varchar(16) not null default 'PENDING'
        comment 'Translation lifecycle. Values: PENDING, READY, FAILED';

alter table grading_localizations
    comment = 'Localized AI feedback and grading explanation read model',
    modify column target_language varchar(16) not null
        comment 'Requested translation language. Values: ko, en, ja',
    modify column source_language varchar(16) not null
        comment 'Original content language. Values: ko, en, ja',
    modify column status varchar(16) not null default 'PENDING'
        comment 'Translation lifecycle. Values: PENDING, READY, FAILED';

alter table question_comment_localizations
    comment = 'Localized community-comment read model',
    modify column target_language varchar(16) not null
        comment 'Requested translation language. Values: ko, en, ja',
    modify column source_language varchar(16) not null
        comment 'Original content language. Values: ko, en, ja',
    modify column status varchar(16) not null default 'PENDING'
        comment 'Translation lifecycle. Values: PENDING, READY, FAILED';
