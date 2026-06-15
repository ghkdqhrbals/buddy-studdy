create index if not exists idx_questions_user_topic_graded_latest
    on questions (user_id, topic, deleted_at, score, answered_at, created_at, id);

create index if not exists idx_question_likes_user_question
    on question_likes (user_id, question_id);
