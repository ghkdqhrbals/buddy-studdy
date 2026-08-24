create index if not exists idx_question_likes_user_created_id
    on question_likes (user_id, created_at desc, id desc, question_id);
