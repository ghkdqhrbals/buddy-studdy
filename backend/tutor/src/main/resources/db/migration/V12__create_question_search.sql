create table if not exists question_search (
    question_id bigint primary key,
    user_id bigint not null,
    topic varchar(255) not null,
    question text not null,
    answer text,
    feedback text,
    explanation text,
    author_display_name varchar(255) not null,
    public_question boolean not null,
    score integer,
    answered_at timestamp with time zone,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index if not exists idx_question_search_public_created on question_search (public_question, score, deleted_at, created_at);
create index if not exists idx_question_search_user on question_search (user_id);

insert into question_search (
    question_id,
    user_id,
    topic,
    question,
    answer,
    feedback,
    explanation,
    author_display_name,
    public_question,
    score,
    answered_at,
    deleted_at,
    created_at,
    updated_at
)
select
    q.id,
    q.user_id,
    q.topic,
    q.question,
    q.answer,
    q.feedback,
    q.explanation,
    u.display_name,
    q.is_public,
    q.score,
    q.answered_at,
    q.deleted_at,
    q.created_at,
    q.updated_at
from questions q
join users u on u.id = q.user_id
where q.user_id is not null
  and q.score is not null
  and q.deleted_at is null
  and not exists (
      select 1
      from question_search qs
      where qs.question_id = q.id
  );
