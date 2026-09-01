-- Rolling-safe expand step: token-aware claims own one opaque UUID, while
-- PROCESSING rows written by an overlapping legacy binary may remain NULL.
-- Terminal CAS also compares the persisted DATETIME(6) claim timestamp, so a
-- legacy reclaim that changes only updated_at still fences the prior worker.
alter table voice_tutor_results
    add column claim_token varchar(36) null
        comment 'Current token-aware summary claim; NULL is legacy rollout compatibility'
        after status,
    add constraint chk_voice_tutor_result_claim_token check (
        claim_token is null
        or (status = 'PROCESSING' and char_length(claim_token) = 36)
    );
