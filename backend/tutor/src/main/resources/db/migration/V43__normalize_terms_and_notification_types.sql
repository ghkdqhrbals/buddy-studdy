insert into terms (code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable)
values
    (
        'TERMS_OF_SERVICE',
        '2026-07-07',
        'ko',
        '서비스 이용약관',
        'https://ghkdqhrbals.github.io/buddy-studdy/terms.html',
        'seed-terms-of-service-2026-07-07-ko',
        timestamp with time zone '2026-07-07 00:00:00+00',
        null,
        true,
        false
    ),
    (
        'PRIVACY_POLICY',
        '2026-07-07',
        'ko',
        '개인정보 처리방침',
        'https://ghkdqhrbals.github.io/buddy-studdy/privacy.html',
        'seed-privacy-policy-2026-07-07-ko',
        timestamp with time zone '2026-07-07 00:00:00+00',
        null,
        true,
        false
    ),
    (
        'MARKETING_NOTIFICATION',
        '2026-07-07',
        'ko',
        '마케팅 정보 수신 동의',
        'https://ghkdqhrbals.github.io/buddy-studdy/terms.html#marketing-notification',
        'seed-marketing-notification-2026-07-07-ko',
        timestamp with time zone '2026-07-07 00:00:00+00',
        null,
        false,
        true
    )
on conflict (code, version, locale)
do update set title = excluded.title,
              url = excluded.url,
              content_hash = excluded.content_hash,
              effective_at = excluded.effective_at,
              retired_at = null,
              required = excluded.required,
              mutable = excluded.mutable;

update terms
   set retired_at = coalesce(retired_at, timestamp with time zone '2026-07-08 00:00:00+00')
 where code in ('INFO_NOTIFICATION', 'NIGHT_MARKETING_NOTIFICATION');

update term_context_requirements
   set retired_at = coalesce(retired_at, timestamp with time zone '2026-07-08 00:00:00+00')
 where context = 'SIGNUP'
   and terms_code not in ('TERMS_OF_SERVICE', 'PRIVACY_POLICY', 'MARKETING_NOTIFICATION');

insert into term_context_requirements (
    context,
    terms_code,
    required,
    mutable,
    permission_code,
    display_order,
    effective_at
)
select v.context,
       v.terms_code,
       v.required,
       v.mutable,
       v.permission_code,
       v.display_order,
       timestamp with time zone '2026-07-07 00:00:00+00'
from (
    select 'SIGNUP' as context, 'TERMS_OF_SERVICE' as terms_code, true as required, false as mutable, 'study:create' as permission_code, 10 as display_order
    union all select 'SIGNUP', 'PRIVACY_POLICY', true, false, null, 20
    union all select 'SIGNUP', 'MARKETING_NOTIFICATION', false, true, 'notification:receive-marketing', 30
) v
where not exists (
    select 1
      from term_context_requirements tcr
     where tcr.context = v.context
       and tcr.terms_code = v.terms_code
       and tcr.retired_at is null
);

update term_context_requirements tcr
   set required = v.required,
       mutable = v.mutable,
       permission_code = v.permission_code,
       display_order = v.display_order
  from (
        select 'TERMS_OF_SERVICE' as terms_code, true as required, false as mutable, 'study:create' as permission_code, 10 as display_order
        union all select 'PRIVACY_POLICY', true, false, null, 20
        union all select 'MARKETING_NOTIFICATION', false, true, 'notification:receive-marketing', 30
       ) v
 where tcr.context = 'SIGNUP'
   and tcr.terms_code = v.terms_code
   and tcr.retired_at is null;
