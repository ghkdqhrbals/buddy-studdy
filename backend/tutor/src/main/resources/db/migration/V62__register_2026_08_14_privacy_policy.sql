insert into terms (
    code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable
) values (
    'PRIVACY_POLICY',
    '2026-08-14',
    'ko',
    '개인정보 처리방침',
    'https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-08-14.html',
    'f9df55f63edb0e2e439f7cb6ab05ce57efbfecfcbbbdd809beb1168191c56dfa',
    timestamp with time zone '2026-08-13 15:00:00+00',
    null,
    true,
    false
)
on conflict (code, version, locale)
do update set title = excluded.title,
              url = excluded.url,
              content_hash = excluded.content_hash,
              effective_at = excluded.effective_at,
              retired_at = null,
              required = excluded.required,
              mutable = excluded.mutable;
