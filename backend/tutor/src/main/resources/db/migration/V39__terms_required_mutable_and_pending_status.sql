alter table terms
    add column if not exists required boolean not null default true,
    add column if not exists mutable boolean not null default false;

update terms
   set required = true,
       mutable = false
 where code in ('TERMS_OF_SERVICE', 'PRIVACY_POLICY');

update terms
   set required = false,
       mutable = true
 where code = 'MARKETING_NOTIFICATION';

update terms
   set retired_at = coalesce(retired_at, now())
 where code in ('INFO_NOTIFICATION', 'NIGHT_MARKETING_NOTIFICATION');

update permission_requirements pr
   set retired_at = coalesce(pr.retired_at, now())
  from permissions p
 where p.id = pr.permission_id
   and p.code in ('notification:receive-info', 'notification:receive-night-marketing')
   and pr.retired_at is null;

update permission_requirements pr
   set retired_at = coalesce(pr.retired_at, now())
  from permissions p
 where p.id = pr.permission_id
   and p.code = 'stats:read'
   and pr.requirement_type = 'TERMS_AGREED'
   and pr.requirement_key = 'PRIVACY_POLICY'
   and pr.retired_at is null;
