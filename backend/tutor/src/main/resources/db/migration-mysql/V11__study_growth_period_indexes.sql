create index idx_questions_user_answered
    on questions (user_id, answered_at, id);
