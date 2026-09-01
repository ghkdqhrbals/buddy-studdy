alter table voice_tutor_transcript_turns
    add column study_answer_turn_id bigint null
        after study_question_turn_id,
    add constraint chk_voice_tutor_study_answer_turn_positive
        check (study_answer_turn_id is null or study_answer_turn_id > 0),
    add constraint chk_voice_tutor_study_answer_turn_role
        check (study_answer_turn_id is null or role = 'TUTOR'),
    add constraint chk_voice_tutor_study_answer_not_question
        check (study_answer_turn_id is null or is_study_question = false),
    add index idx_voice_tutor_answer_feedback (session_id, study_answer_turn_id, sequence_number);
