alter table voice_tutor_transcript_turns
    add column asked_study_question boolean not null default false
        comment 'Server semantic attestation that this USER turn asks a substantive question about its saved lesson focus',
    add constraint chk_voice_tutor_asked_study_question
        check (asked_study_question = false or role = 'USER'),
    add index idx_voice_tutor_asked_study_question
        (session_id, asked_study_question, sequence_number);
