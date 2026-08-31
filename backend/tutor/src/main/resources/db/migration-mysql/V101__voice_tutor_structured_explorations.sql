-- Additive, voice-only evidence; old summaries keep NULL and decode as [].
-- No question records, question quota, statistics, or drafts are changed.
alter table voice_tutor_results
    add column explorations_json json null
        comment 'Bounded topic/question/answer/feedback evidence referencing this voice session transcript',
    add constraint chk_voice_tutor_result_explorations check (
        explorations_json is null or (
            json_type(explorations_json) = 'ARRAY'
            and json_length(explorations_json) <= 12
            and octet_length(explorations_json) <= 262144
        )
    );
