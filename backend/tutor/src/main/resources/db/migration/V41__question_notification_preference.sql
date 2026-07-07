insert into notification_preferences (user_id, device_id, preference_key, enabled, created_at, updated_at)
select user_id,
       device_id,
       'question_notification',
       enabled,
       created_at,
       updated_at
  from notification_preferences source
 where source.preference_key = 'info_notification'
   and source.user_id is not null
   and not exists (
       select 1
         from notification_preferences target
        where target.user_id = source.user_id
          and target.preference_key = 'question_notification'
   );

insert into notification_preferences (user_id, device_id, preference_key, enabled, created_at, updated_at)
select null,
       device_id,
       'question_notification',
       enabled,
       created_at,
       updated_at
  from notification_preferences source
 where source.preference_key = 'info_notification'
   and source.user_id is null
   and not exists (
       select 1
         from notification_preferences target
        where target.user_id is null
          and target.device_id = source.device_id
          and target.preference_key = 'question_notification'
   );

insert into permission_requirements (
    permission_id,
    requirement_type,
    requirement_key,
    operator,
    requirement_value,
    failure_code,
    effective_at
)
select p.id,
       v.requirement_type,
       v.requirement_key,
       v.operator,
       v.requirement_value,
       v.failure_code,
       timestamp with time zone '2026-07-08 00:00:00+00'
  from permissions p
  join (
        select 'PREFERENCE_ENABLED' as requirement_type,
               'question_notification' as requirement_key,
               'EQ' as operator,
               'true' as requirement_value,
               'NOTIFICATION_PREFERENCE_DISABLED' as failure_code
        union all
        select 'DEVICE_REGISTERED',
               'apns_token',
               'EXISTS',
               null,
               'DEVICE_NOT_REGISTERED'
       ) v on true
 where p.code = 'notification:receive-info'
   and not exists (
       select 1
         from permission_requirements pr
        where pr.permission_id = p.id
          and pr.requirement_type = v.requirement_type
          and pr.requirement_key = v.requirement_key
          and pr.operator = v.operator
          and pr.retired_at is null
   );
