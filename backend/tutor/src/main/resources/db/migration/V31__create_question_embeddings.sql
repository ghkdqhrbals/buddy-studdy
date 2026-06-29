create table if not exists question_embeddings (
    question_id bigint primary key references questions(id) on delete cascade,
    user_id bigint not null,
    study_id bigint not null,
    topic varchar(255) not null,
    topic_key varchar(255) not null,
    question text not null,
    embedding text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index if not exists idx_question_embeddings_study_topic_created
    on question_embeddings (study_id, topic_key, created_at desc, question_id desc);

create index if not exists idx_question_embeddings_user_topic_created
    on question_embeddings (user_id, topic_key, created_at desc, question_id desc);
