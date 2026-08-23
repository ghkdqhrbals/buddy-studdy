insert into terms (
    code, version, locale, title, url, content_hash, effective_at, retired_at, required, mutable
) values
    (
        'TERMS_OF_SERVICE',
        '2026-07-30',
        'ko',
        '서비스 이용약관',
        'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-30.html',
        '00544e21ee0921edc23d70c51d1977a57c3ddb9fb5d9d76ba7479fb4019a7edd',
        '2026-07-29 15:00:00.000000',
        null,
        true,
        false
    ),
    (
        'PRIVACY_POLICY',
        '2026-07-30',
        'ko',
        '개인정보 처리방침',
        'https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-07-30.html',
        'f6af1a6389b4b7bb9a1221da5ce7b3671780300e3a61448273231a3d130061b6',
        '2026-07-29 15:00:00.000000',
        null,
        true,
        false
    ),
    (
        'MARKETING_NOTIFICATION',
        '2026-07-30',
        'ko',
        '마케팅 정보 수신 동의',
        'https://ghkdqhrbals.github.io/buddy-studdy/marketing-consent-2026-07-30.html',
        '984adea3e746ce793405f431eb8a554419d64cc11633d697d7962adc6fa4a12e',
        '2026-07-29 15:00:00.000000',
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
