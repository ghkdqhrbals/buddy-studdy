alter table stream_consumer_inbox
    drop check chk_stream_consumer_inbox_status,
    add column last_error_type varchar(255) null after lease_expires_at,
    add column failed_at datetime(6) null after completed_at,
    add constraint chk_stream_consumer_inbox_status
        check (status in ('PROCESSING', 'SUCCEEDED', 'FAILED'));

create table stream_consumer_inbox_attempts (
    id bigint not null auto_increment,
    event_id varchar(120) not null,
    consumer_group varchar(120) not null,
    correlation_id varchar(36) not null,
    attempt int not null,
    claim_token varchar(36) not null,
    status varchar(24) not null,
    error_type varchar(255),
    error_message varchar(1000),
    started_at datetime(6) not null,
    finished_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint uq_stream_consumer_inbox_attempt
        unique (event_id, consumer_group, attempt),
    constraint chk_stream_consumer_inbox_attempt_status
        check (status in ('PROCESSING', 'RETRY_SCHEDULED', 'LEASE_EXPIRED', 'SUCCEEDED', 'FAILED')),
    index idx_stream_consumer_inbox_attempt_group_id (consumer_group, id desc),
    index idx_stream_consumer_inbox_attempt_status_id (status, id desc),
    index idx_stream_consumer_inbox_attempt_correlation (correlation_id, id desc),
    constraint fk_stream_consumer_inbox_attempt
        foreign key (event_id, consumer_group)
        references stream_consumer_inbox (event_id, consumer_group)
        on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
