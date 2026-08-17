-- One iOS installation can be used to sign in and out of multiple accounts.
-- A device has one current owner, so an active session belonging to any other
-- user must not remain eligible for push delivery.
update user_devices ud
left join devices device on device.device_id = ud.device_id
set ud.revoked_at = utc_timestamp(6),
    ud.updated_at = utc_timestamp(6)
where ud.logged_out_at is null
  and ud.revoked_at is null
  and (ud.session_expires_at is null or ud.session_expires_at > utc_timestamp(6))
  and (
      device.device_id is null
      or device.user_id is null
      or device.user_id <> ud.user_id
  );
