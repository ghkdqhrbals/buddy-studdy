-- Preserve only boundaries that can be proven from the V114 state. In particular,
-- remaining_seconds = 0 alone is insufficient after an administrator lowers a
-- limit, and an ACTIVE legacy row whose hard end is still reservation-anchored
-- cannot consume its whole reservation from connected_at.
update voice_tutor_sessions session
join user_voice_quota quota
  on quota.user_id = session.user_id
 and quota.period_started_at = session.period_started_at
 and quota.period_ends_at = session.period_ends_at
set session.monthly_quota_exhausts_at_hard_end = true
where session.status in ('READY', 'ACTIVE')
  and session.finalized_at is null
  and session.reserved_seconds > 0
  and session.reserved_seconds <= session.max_session_seconds
  and quota.reserved_seconds = session.reserved_seconds
  and cast(quota.base_seconds as signed) =
      cast(quota.used_seconds as signed) + cast(quota.reserved_seconds as signed)
  -- This exact effective limit is the product's effectively-unlimited sentinel.
  and quota.base_seconds <> 31536000
  and session.hard_ends_at <= session.period_ends_at
  and (
        (
            session.status = 'READY'
            and session.provider_session_id is null
            and session.connected_at is null
            and session.hard_ends_at = timestampadd(second, session.reserved_seconds, session.created_at)
        )
        or
        (
            session.status = 'ACTIVE'
            and left(session.provider_session_id, 4) = 'rtc_'
            and session.connected_at is not null
            and session.hard_ends_at = timestampadd(second, session.reserved_seconds, session.connected_at)
        )
      );
