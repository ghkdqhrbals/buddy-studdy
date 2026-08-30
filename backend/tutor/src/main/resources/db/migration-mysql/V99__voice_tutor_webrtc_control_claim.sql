alter table voice_tutor_sessions
    add column control_connection_id varchar(36) null after provider_session_id,
    add column control_claimed_at datetime(6) null after control_connection_id,
    add constraint uq_voice_tutor_sessions_control_connection unique (control_connection_id);

create table voice_tutor_webrtc_cleanup_outbox (
    call_id varchar(191) not null,
    user_id bigint not null,
    session_id varchar(36) not null,
    attached_at datetime(6) null,
    recover_after datetime(6) not null,
    claimed_at datetime(6) null,
    claim_token varchar(36) null,
    attempt_count int unsigned not null default 0,
    last_error varchar(1000) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (call_id),
    key idx_voice_tutor_webrtc_cleanup_claim (recover_after, claimed_at, call_id),
    constraint chk_voice_tutor_webrtc_cleanup_call check (left(call_id, 4) = 'rtc_'),
    constraint chk_voice_tutor_webrtc_cleanup_attempt check (attempt_count >= 0)
) engine=InnoDB comment='FK-free durable cleanup markers for externally-created OpenAI WebRTC calls';
