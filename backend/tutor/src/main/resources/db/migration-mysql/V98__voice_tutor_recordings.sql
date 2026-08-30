alter table voice_tutor_sessions
    add column recording_consented_at datetime(6) null
        comment 'Explicit user consent captured when the session was reserved; null forbids recording upload'
        after accepted_audio_bytes,
    add column recording_consent_version varchar(64) null
        comment 'Version of the recording consent text accepted by the user'
        after recording_consented_at,
    add constraint uq_voice_tutor_sessions_recording_owner unique (id, user_id),
    add constraint chk_voice_tutor_sessions_recording_consent check (
        (recording_consented_at is null and recording_consent_version is null)
        or (recording_consented_at is not null and recording_consent_version is not null)
    );

create table voice_tutor_recordings (
    session_id varchar(36) primary key,
    user_id bigint not null,
    object_key varchar(512) not null
        comment 'Private server-generated S3 object key; never returned as a standalone API field',
    status varchar(24) not null
        comment 'PENDING, AVAILABLE, FAILED, or DELETED',
    content_type varchar(64) not null,
    expected_bytes bigint unsigned not null,
    actual_bytes bigint unsigned null,
    sha256_hex char(64) not null,
    duration_milliseconds bigint unsigned not null,
    consented_at datetime(6) not null,
    consent_version varchar(64) not null,
    upload_expires_at datetime(6) not null,
    retained_until datetime(6) not null,
    completed_at datetime(6) null,
    deleted_at datetime(6) null,
    failure_message varchar(255) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_voice_tutor_recordings_object_key unique (object_key),
    constraint fk_voice_tutor_recordings_session_owner foreign key (session_id, user_id)
        references voice_tutor_sessions(id, user_id) on delete cascade,
    constraint chk_voice_tutor_recordings_status
        check (status in ('PENDING', 'AVAILABLE', 'FAILED', 'DELETED')),
    constraint chk_voice_tutor_recordings_content_type
        check (content_type = 'audio/mp4'),
    constraint chk_voice_tutor_recordings_sizes
        check (expected_bytes > 0 and (actual_bytes is null or actual_bytes > 0)),
    constraint chk_voice_tutor_recordings_duration
        check (duration_milliseconds > 0),
    constraint chk_voice_tutor_recordings_retention
        check (retained_until > created_at),
    index idx_voice_tutor_recordings_user_status (user_id, status, created_at desc),
    index idx_voice_tutor_recordings_retention (status, retained_until, session_id),
    index idx_voice_tutor_recordings_upload_cleanup (status, upload_expires_at, deleted_at, session_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Private mixed AAC/M4A Voice Tutor recordings; binary audio remains in private S3 only';

create table voice_tutor_recording_prefix_cleanups (
    user_id bigint primary key
        comment 'Permanent withdrawn-owner tombstone retained only to derive the deterministic S3 prefix; intentionally has no users FK',
    cleanup_until datetime(6) not null
        comment 'Boundary between frequent cleanup and permanent low-frequency cleanup retries',
    next_attempt_at datetime(6) not null,
    last_attempted_at datetime(6) null,
    attempt_count bigint unsigned not null default 0,
    last_failure_message varchar(255) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint chk_voice_tutor_recording_prefix_cleanup_window
        check (cleanup_until >= created_at),
    index idx_voice_tutor_recording_prefix_cleanups_due (next_attempt_at, user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Permanent FK-free tombstones for post-withdrawal Voice Tutor recording prefix deletion';

insert into scheduled_jobs (
    job_name, enabled, schedule_type, schedule_value, max_retry_count, timeout_seconds, lock_seconds
) values (
    'voice-tutor-recording-retention', true, 'CRON', '0 * * * * * UTC', 3, 900, 900
) on duplicate key update
    schedule_type = values(schedule_type),
    schedule_value = values(schedule_value),
    max_retry_count = values(max_retry_count),
    timeout_seconds = values(timeout_seconds),
    lock_seconds = values(lock_seconds);
