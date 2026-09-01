alter table voice_tutor_transcript_turns
    add constraint chk_voice_tutor_study_answer_role
        check (study_question_turn_id is null or role = 'USER'),
    add constraint chk_voice_tutor_learning_attestation_exclusive
        check (study_question_turn_id is null or asked_study_question = false);
