alter table voice_tutor_transcript_turns
    add column study_question_turn_id bigint null
        comment 'Exact preceding TUTOR turn durably verified as the substantive study question answered by this USER turn',
    add constraint chk_voice_tutor_study_question_turn
        check (study_question_turn_id is null or study_question_turn_id > 0),
    add index idx_voice_tutor_verified_exchange (session_id, study_question_turn_id);

