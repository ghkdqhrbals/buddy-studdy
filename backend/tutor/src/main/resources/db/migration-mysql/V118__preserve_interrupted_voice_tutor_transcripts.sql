ALTER TABLE voice_tutor_transcript_turns
    ADD COLUMN interrupted BOOLEAN NOT NULL DEFAULT FALSE;
