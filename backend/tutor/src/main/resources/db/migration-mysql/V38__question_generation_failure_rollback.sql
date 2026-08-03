alter table question_generation_sagas
    add column rollback_completed_at datetime(6) null after completed_at;

alter table question_generation_sagas
    drop index uq_question_generation_saga_active_topic,
    drop column active_topic_id;

alter table question_generation_sagas
    add column active_topic_id bigint generated always as (
        case
            when status in ('QUEUED', 'GENERATING', 'TRANSLATING')
                or (status = 'FAILED' and rollback_completed_at is null)
            then topic_id
            else null
        end
    ) stored after status,
    add constraint uq_question_generation_saga_active_topic unique (user_id, active_topic_id),
    add index idx_question_generation_saga_rollback (status, rollback_completed_at, updated_at);
