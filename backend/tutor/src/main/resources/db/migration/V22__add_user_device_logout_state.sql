alter table user_devices
    add column if not exists logged_out_at timestamp with time zone,
    add column if not exists revoked_at timestamp with time zone;

create index if not exists idx_user_devices_active_user
    on user_devices (user_id, logged_out_at, revoked_at, session_expires_at);

create index if not exists idx_user_devices_active_device
    on user_devices (device_id, logged_out_at, revoked_at, session_expires_at);
