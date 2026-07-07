alter table app_notifications
    alter column user_id drop not null;

alter table app_notifications
    add column if not exists device_id varchar(191);

create index if not exists idx_app_notifications_device_visible_created
    on app_notifications (device_id, deleted_at, created_at desc, id desc);

create index if not exists idx_app_notifications_device_unread
    on app_notifications (device_id, read_at, deleted_at);
