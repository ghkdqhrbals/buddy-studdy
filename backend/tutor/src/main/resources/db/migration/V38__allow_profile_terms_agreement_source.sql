alter table user_term_agreements
    drop constraint if exists chk_user_term_agreements_source;

alter table user_term_agreements
    add constraint chk_user_term_agreements_source
        check (source in ('SIGNUP', 'SETTINGS', 'PROFILE', 'REQUIRED_GATE', 'MIGRATION'));
