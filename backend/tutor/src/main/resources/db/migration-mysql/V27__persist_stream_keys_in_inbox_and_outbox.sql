alter table stream_consumer_inbox
    add column stream_key varchar(255) null after correlation_id;

update stream_consumer_inbox
set stream_key = case consumer_group
    when 'bs-backend-question-generation' then 'buddystudy-question-generation-v1'
    when 'bs-backend-question-translation' then 'buddystudy-question-generated-v1'
    when 'bs-backend-content-translation' then 'buddystudy-content-translation-v1'
    else 'unknown'
end
where stream_key is null;

alter table stream_consumer_inbox
    modify column stream_key varchar(255) not null,
    add index idx_stream_consumer_inbox_stream_group (stream_key, consumer_group, updated_at);

alter table stream_consumer_inbox_attempts
    add column stream_key varchar(255) null after correlation_id;

update stream_consumer_inbox_attempts attempts
join stream_consumer_inbox inbox
  on inbox.event_id = attempts.event_id
 and inbox.consumer_group = attempts.consumer_group
set attempts.stream_key = inbox.stream_key
where attempts.stream_key is null;

alter table stream_consumer_inbox_attempts
    modify column stream_key varchar(255) not null,
    add index idx_stream_consumer_inbox_attempt_stream (stream_key, consumer_group, id desc);

alter table redis_event_outbox
    add column stream_key varchar(255) null after event_type,
    add column redis_record_id varchar(128) null after stream_key,
    add index idx_redis_event_outbox_stream_published (stream_key, published_at);

update redis_event_outbox
set stream_key = case event_type
    when 'NOTIFICATION_REQUESTED' then 'buddystudy-events-v1'
    when 'ACCOUNT_WITHDRAWN' then 'buddystudy-events-v1'
    when 'ANSWER_GRADING_REQUESTED' then 'buddystudy-events-v1'
    when 'QUESTION_GENERATION_REQUESTED' then 'buddystudy-question-generation-v1'
    when 'QUESTION_GENERATED' then 'buddystudy-question-generated-v1'
    when 'CONTENT_TRANSLATION_REQUESTED' then 'buddystudy-content-translation-v1'
    else null
end
where status = 'PUBLISHED'
  and stream_key is null;

alter table question_push_outbox
    add column stream_key varchar(255) null after status,
    add column redis_record_id varchar(128) null after stream_key,
    add index idx_question_push_outbox_stream_published (stream_key, published_at);

update question_push_outbox
set stream_key = 'buddystudy-push-v1'
where status = 'PUBLISHED'
  and stream_key is null;
