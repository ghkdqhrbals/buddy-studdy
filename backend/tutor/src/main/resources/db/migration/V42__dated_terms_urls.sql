update terms
   set url = case code
       when 'TERMS_OF_SERVICE' then 'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-07.html'
       when 'PRIVACY_POLICY' then 'https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-07-07.html'
       when 'INFO_NOTIFICATION' then 'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-07.html#info-notification'
       when 'MARKETING_NOTIFICATION' then 'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-07.html#marketing-notification'
       when 'NIGHT_MARKETING_NOTIFICATION' then 'https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-07.html#night-marketing-notification'
       else url
   end
 where version = '2026-07-07'
   and locale = 'ko'
   and code in (
       'TERMS_OF_SERVICE',
       'PRIVACY_POLICY',
       'INFO_NOTIFICATION',
       'MARKETING_NOTIFICATION',
       'NIGHT_MARKETING_NOTIFICATION'
   );
