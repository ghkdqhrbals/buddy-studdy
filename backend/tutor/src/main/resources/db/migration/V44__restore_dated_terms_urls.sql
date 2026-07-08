update terms
set url = case code
    when 'TERMS_OF_SERVICE' then 'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-07.html'
    when 'PRIVACY_POLICY' then 'https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-07-07.html'
    when 'MARKETING_NOTIFICATION' then 'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-07.html#marketing-notification'
    else url
end
where code in ('TERMS_OF_SERVICE', 'PRIVACY_POLICY', 'MARKETING_NOTIFICATION')
  and version = '2026-07-07';
