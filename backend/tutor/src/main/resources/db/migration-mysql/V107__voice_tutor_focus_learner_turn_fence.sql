alter table voice_tutor_lesson_focuses
    add column learner_turn_id bigint null after study_id,
    add unique key uq_voice_tutor_focus_learner_turn (session_id, learner_turn_id),
    add constraint chk_voice_tutor_lesson_focus_learner_turn
        check (learner_turn_id is null or learner_turn_id > 0);
