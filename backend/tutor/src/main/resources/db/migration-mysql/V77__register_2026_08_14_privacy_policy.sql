insert into terms (
    code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable
) values (
    'PRIVACY_POLICY',
    '2026-08-14',
    'ko',
    '개인정보 처리방침',
    'https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-08-14.html',
    'f9df55f63edb0e2e439f7cb6ab05ce57efbfecfcbbbdd809beb1168191c56dfa',
    '2026-08-13 15:00:00.000000',
    null,
    true,
    false
)
on duplicate key update
    title = values(title),
    url = values(url),
    content_hash = values(content_hash),
    effective_at = values(effective_at),
    retired_at = null,
    required = values(required),
    mutable = values(mutable);
