alter table user_membership_tiers
    add column monthly_voice_seconds_limit int unsigned not null default 0
        comment 'Monthly server-metered Voice Tutor allowance in seconds; zero grants no allowance'
        after monthly_question_limit;

update user_membership_tiers
set monthly_voice_seconds_limit = case tier_code
    when 'TIER2' then 18000
    when 'TIER3' then 18000
    else 0
end,
updated_at = utc_timestamp(6);

create table user_voice_quota (
    user_id bigint primary key
        comment 'BuddyStudy user owning this authoritative monthly Voice Tutor quota projection',
    tier_code varchar(32) not null
        comment 'Effective membership tier used to resolve the Voice Tutor allowance',
    anchor_at datetime(6) not null
        comment 'UTC account-created anchor used for drift-free monthly windows',
    period_started_at datetime(6) not null
        comment 'Inclusive UTC start of the materialized quota period',
    period_ends_at datetime(6) not null
        comment 'Exclusive UTC end and reset instant of the materialized quota period',
    limit_override_seconds int unsigned null
        comment 'Persistent administrator-set user cap; null follows the effective tier default',
    base_seconds int unsigned not null
        comment 'Effective Voice Tutor cap after applying the optional persistent user override',
    used_seconds int unsigned not null default 0
        comment 'Finalized server-metered Voice Tutor seconds charged in this period',
    reserved_seconds int unsigned not null default 0
        comment 'Seconds reserved by the single in-flight Voice Tutor session',
    remaining_seconds int generated always as (
        greatest(
            0,
            cast(base_seconds as signed)
                - cast(used_seconds as signed)
                - cast(reserved_seconds as signed)
        )
    ) stored
        comment 'Generated available allowance: max(0, base - used - reserved)',
    version bigint unsigned not null default 0
        comment 'Monotonic revision for serialized quota mutations',
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_user_voice_quota_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_user_voice_quota_tier foreign key (tier_code) references user_membership_tiers(tier_code),
    constraint chk_user_voice_quota_period check (period_ends_at > period_started_at),
    index idx_user_voice_quota_period_end (period_ends_at, user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Authoritative monthly Pro Voice Tutor wall-clock quota; application transactions own every mutation';

create table voice_tutor_sessions (
    id varchar(36) primary key
        comment 'Application-generated UUID exposed by the Voice Tutor API',
    user_id bigint not null,
    study_id bigint null,
    idempotency_key varchar(191) not null
        comment 'User-scoped key making session creation retry-safe',
    provider_session_id varchar(191) null
        comment 'OpenAI Realtime session identifier observed from session.created',
    status varchar(24) not null
        comment 'READY, ACTIVE, ENDING, COMPLETED, or FAILED',
    active_user_id bigint generated always as (
        case when status in ('READY', 'ACTIVE', 'ENDING') then user_id else null end
    ) stored
        comment 'Generated single-active-session lock; terminal rows expose null',
    result_status varchar(24) not null default 'PENDING'
        comment 'PENDING, PROCESSING, COMPLETED, or FAILED',
    language varchar(8) not null,
    model varchar(128) not null,
    voice varchar(64) not null,
    topic_snapshot varchar(255) not null,
    difficulty_snapshot int not null,
    period_started_at datetime(6) not null,
    period_ends_at datetime(6) not null,
    reserved_seconds int unsigned not null,
    charged_seconds int unsigned not null default 0,
    max_session_seconds int unsigned not null,
    hard_ends_at datetime(6) not null,
    connected_at datetime(6) null,
    relay_heartbeat_at datetime(6) null
        comment 'Latest liveness signal from the owning backend WebSocket relay',
    accepted_audio_bytes bigint unsigned not null default 0
        comment 'Decoded PCM bytes accepted by the relay for server-side cost-aware settlement',
    ended_at datetime(6) null,
    finalized_at datetime(6) null,
    finalization_key varchar(191) null
        comment 'Unique idempotency key for terminal quota settlement',
    end_reason varchar(64) null,
    failure_code varchar(64) null,
    failure_message varchar(1000) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint uq_voice_tutor_sessions_user_idempotency unique (user_id, idempotency_key),
    constraint uq_voice_tutor_sessions_active_user unique (active_user_id),
    constraint uq_voice_tutor_sessions_provider unique (provider_session_id),
    constraint uq_voice_tutor_sessions_finalization unique (finalization_key),
    -- MySQL forbids cascading a base column that is referenced by the stored
    -- active_user_id generated column. Account withdrawal deletes these rows
    -- explicitly before scrubbing the retained users row.
    constraint fk_voice_tutor_sessions_user foreign key (user_id) references users(id),
    constraint fk_voice_tutor_sessions_study foreign key (study_id) references studies(id) on delete set null,
    constraint chk_voice_tutor_sessions_status check (status in ('READY', 'ACTIVE', 'ENDING', 'COMPLETED', 'FAILED')),
    constraint chk_voice_tutor_sessions_result_status check (result_status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    constraint chk_voice_tutor_sessions_language check (language in ('ko', 'en', 'ja')),
    constraint chk_voice_tutor_sessions_period check (period_ends_at > period_started_at),
    constraint chk_voice_tutor_sessions_seconds check (
        reserved_seconds > 0
        and reserved_seconds <= 3600
        and max_session_seconds > 0
        and max_session_seconds <= 3600
        and charged_seconds <= reserved_seconds
    ),
    index idx_voice_tutor_sessions_user_created (user_id, created_at desc, id desc),
    index idx_voice_tutor_sessions_user_study_created (user_id, study_id, created_at desc, id desc),
    index idx_voice_tutor_sessions_hard_end (status, hard_ends_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Durable Voice Tutor session lifecycle and exactly-once server-metered settlement state';

create table voice_tutor_transcript_turns (
    id bigint auto_increment primary key,
    session_id varchar(36) not null,
    provider_item_id varchar(191) not null
        comment 'OpenAI conversation item identifier used for idempotent transcript capture',
    role varchar(16) not null
        comment 'USER or TUTOR',
    transcript mediumtext not null,
    sequence_number bigint unsigned not null,
    occurred_at datetime(6) not null,
    created_at datetime(6) not null,
    constraint uq_voice_tutor_turn_provider_item unique (session_id, provider_item_id, role),
    constraint uq_voice_tutor_turn_sequence unique (session_id, sequence_number),
    constraint fk_voice_tutor_turn_session foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
    constraint chk_voice_tutor_turn_role check (role in ('USER', 'TUTOR')),
    index idx_voice_tutor_turn_session_time (session_id, occurred_at, id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Transcript-only Voice Tutor learning evidence; raw audio is intentionally not persisted';

create table voice_tutor_results (
    session_id varchar(36) primary key,
    status varchar(24) not null
        comment 'PROCESSING, COMPLETED, or FAILED',
    summary_markdown mediumtext null,
    strengths_json text null,
    improvements_json text null,
    next_steps_json text null,
    model varchar(128) null,
    prompt_version varchar(64) not null,
    error_message varchar(1000) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    constraint fk_voice_tutor_result_session foreign key (session_id) references voice_tutor_sessions(id) on delete cascade,
    constraint chk_voice_tutor_result_status check (status in ('PROCESSING', 'COMPLETED', 'FAILED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci
comment='Structured, app-visible learning summary generated after a Voice Tutor session';

insert into permissions (code, description, requires_active_account, created_at, updated_at)
values ('voice-tutor:read', 'Read private Voice Tutor transcripts and learning results', true, utc_timestamp(6), utc_timestamp(6))
on duplicate key update
    description = values(description),
    requires_active_account = values(requires_active_account),
    updated_at = values(updated_at);

insert ignore into role_permissions (role_id, permission_id, created_at, updated_at)
select role.id, permission.id, utc_timestamp(6), utc_timestamp(6)
from roles role
join permissions permission on permission.code = 'voice-tutor:read'
where role.code in ('REGISTERED_USER', 'ADMIN');
