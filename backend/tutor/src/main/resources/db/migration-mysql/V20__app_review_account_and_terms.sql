insert into terms (
    code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable
) values
    (
        'TERMS_OF_SERVICE',
        '2026-07-28',
        'ko',
        '서비스 이용약관',
        'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-28.html',
        'c32e844f5071a1bf4e1673d82dad2dfa883661f18ca0c86722d02432c7bc75af',
        '2026-07-28 00:00:00.000000',
        null,
        true,
        false
    ),
    (
        'MARKETING_NOTIFICATION',
        '2026-07-28',
        'ko',
        '마케팅 정보 수신 동의',
        'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-28.html#marketing-notification',
        'c32e844f5071a1bf4e1673d82dad2dfa883661f18ca0c86722d02432c7bc75af',
        '2026-07-28 00:00:00.000000',
        null,
        false,
        true
    )
on duplicate key update
    title = values(title),
    url = values(url),
    content_hash = values(content_hash),
    effective_at = values(effective_at),
    retired_at = null,
    required = values(required),
    mutable = values(mutable);

insert into users (
    provider,
    provider_id,
    password_hash,
    status,
    email,
    display_name,
    avatar_url,
    avatar_symbol_name,
    avatar_color_seed,
    bio,
    allow_public_questions,
    openai_api_key_cipher,
    openai_model,
    app_language,
    free_system_question_count,
    avatar_mode,
    avatar_config,
    created_at,
    updated_at
) values (
    'EMAIL',
    'ghkdqhrbals+appreview@gmail.com',
    '35eeaa50ac4da376b1b632ea25d467ae0f49acef9fd1e3043604467da25794da',
    'ACTIVE',
    'ghkdqhrbals+appreview@gmail.com',
    'AppReview-20260728',
    null,
    'person.crop.circle',
    'avatar-color-mint',
    'Account reserved for App Review.',
    false,
    null,
    'gpt-5.4',
    'en',
    0,
    'BUILDER',
    null,
    utc_timestamp(6),
    utc_timestamp(6)
)
on duplicate key update
    password_hash = values(password_hash),
    status = 'ACTIVE',
    email = values(email),
    app_language = 'en',
    allow_public_questions = false,
    updated_at = utc_timestamp(6);

insert into user_roles (user_id, role_id, created_at, updated_at)
select reviewer.id, role.id, utc_timestamp(6), utc_timestamp(6)
from users reviewer
join roles role on role.code = 'REGISTERED_USER'
where reviewer.provider = 'EMAIL'
  and reviewer.provider_id = 'ghkdqhrbals+appreview@gmail.com'
  and not exists (
      select 1
      from user_roles existing
      where existing.user_id = reviewer.id
        and existing.role_id = role.id
  );

insert into user_term_agreements (
    user_id,
    device_id,
    terms_id,
    action,
    source,
    ip_address,
    user_agent,
    app_version,
    created_at
)
select reviewer.id, null, active_terms.id, 'AGREED', 'MIGRATION', null, 'App Store Review setup', '1.0.16', utc_timestamp(6)
from users reviewer
join (
    select ranked.id
    from (
        select
            term.id,
            term.code,
            row_number() over (
                partition by term.code
                order by term.effective_at desc, term.id desc
            ) as latest_rank
        from terms term
        where term.code in ('TERMS_OF_SERVICE', 'PRIVACY_POLICY')
          and term.effective_at <= utc_timestamp(6)
          and (term.retired_at is null or term.retired_at > utc_timestamp(6))
    ) ranked
    where ranked.latest_rank = 1
) active_terms
where reviewer.provider = 'EMAIL'
  and reviewer.provider_id = 'ghkdqhrbals+appreview@gmail.com'
  and not exists (
      select 1
      from user_term_agreements existing
      where existing.user_id = reviewer.id
        and existing.terms_id = active_terms.id
        and existing.action = 'AGREED'
  );
