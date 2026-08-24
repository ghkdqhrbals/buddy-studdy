alter table question_likes
    add index idx_question_likes_user_created_id (user_id, created_at desc, id desc, question_id),
    algorithm=inplace,
    lock=none;
