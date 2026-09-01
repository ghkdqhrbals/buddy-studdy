alter table voice_tutor_transcript_turns
    add column is_study_question boolean not null default false
        comment 'Server-owned proof that this completed TUTOR item was issued as a substantive study question',
    add constraint chk_voice_tutor_is_study_question
        check (is_study_question = false or role = 'TUTOR'),
    add index idx_voice_tutor_study_question
        (session_id, is_study_question, sequence_number);
