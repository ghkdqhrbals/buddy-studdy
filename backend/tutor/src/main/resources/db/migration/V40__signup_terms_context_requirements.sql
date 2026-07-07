create table if not exists term_context_requirements (
    id bigserial primary key,
    context varchar(64) not null,
    terms_code varchar(120) not null,
    required boolean not null,
    mutable boolean not null,
    permission_code varchar(120),
    display_order integer not null,
    effective_at timestamptz not null,
    retired_at timestamptz,
    created_at timestamptz not null default now()
);

create index if not exists idx_term_context_requirements_active
    on term_context_requirements (context, display_order, terms_code, effective_at desc, id desc)
    where retired_at is null;

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
       'TERMS_AGREED',
       'MARKETING_NOTIFICATION',
       'LATEST',
       null,
       'TERMS_AGREEMENT_REQUIRED',
       timestamp with time zone '2026-07-07 00:00:00+00'
  from permissions p
 where p.code = 'notification:receive-marketing'
   and not exists (
       select 1
         from permission_requirements pr
        where pr.permission_id = p.id
          and pr.requirement_type = 'TERMS_AGREED'
          and pr.requirement_key = 'MARKETING_NOTIFICATION'
          and pr.operator = 'LATEST'
          and pr.retired_at is null
   );
