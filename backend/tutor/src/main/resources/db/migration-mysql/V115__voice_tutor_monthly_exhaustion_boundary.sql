alter table voice_tutor_sessions
    add column monthly_quota_exhausts_at_hard_end boolean not null default false
        comment 'Reservation-time proof that this session owns all finite monthly seconds remaining'
        after hard_ends_at;
