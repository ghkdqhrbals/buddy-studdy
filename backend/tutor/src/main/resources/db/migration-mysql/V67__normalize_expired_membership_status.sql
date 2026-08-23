update user_memberships
set status = 'INACTIVE',
    updated_at = utc_timestamp(6)
where status = 'ACTIVE'
  and expires_at is not null
  and expires_at <= utc_timestamp(6);
