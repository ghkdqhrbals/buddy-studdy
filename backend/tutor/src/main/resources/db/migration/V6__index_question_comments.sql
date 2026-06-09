create index if not exists idx_question_comments_question_active_created
    on question_comments (question_id, deleted_at, created_at desc);
