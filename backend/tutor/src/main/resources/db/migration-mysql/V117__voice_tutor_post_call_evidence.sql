-- Native realtime records raw, clean completed turns without semantic gates.
-- The existing sequence_number receives the server-frozen conversation order.
alter table voice_tutor_transcript_turns
    add column post_call_evidence boolean not null default false;

-- A lost ASR/source write invalidates the whole post-call evidence set.
alter table voice_tutor_sessions
    add column post_call_transcript_incomplete boolean not null default false;
