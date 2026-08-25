insert into terms (
    code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable
) values (
    'PRIVACY_POLICY',
    '2026-08-25',
    'ko',
    '개인정보 처리방침',
    'https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-08-25.html',
    '13f2e4925ad4a28f39304570e68309a960c2460b3bdf87466898718648228a21',
    '9999-12-31 00:00:00.000000',
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
