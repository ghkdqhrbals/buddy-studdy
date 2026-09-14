# Translation diagnostics — 2026-09-15

## Production observations

The administrator provider check at 2026-09-14T20:20:32Z (05:20 KST)
returned LibreTranslate UP in 223 ms and OpenAI UP in 1,540 ms. The old
LibreTranslate probe only called `/languages`; it did not run inference.
The returned language list includes Korean, English and Japanese.

The latest translation requests were from 2026-09-14T11:38:45Z. History
IDs 843–848 show HTTP 200 and SUCCEEDED, but their stored `translatedText`
values are empty despite non-empty inputs of 7–205 characters. Requests
849–853 timed out at approximately 5 seconds. Stored bodies were reconstructed
from the decoded DTO, so these records alone do not distinguish raw provider
output from decoder behavior. A decoder defect has not been established from these historical records.

## Correction

- Parse the exact `translatedText` wire field and reject missing, mistyped,
  empty or malformed output before recording provider success.
- Keep the actual response body for successful history entries rather than
  reconstructing a default-valued DTO.
- Make the administrator LibreTranslate check perform one short synthetic
  Korean-to-English translation. An HTTP 200 with empty output is DOWN.
- Keep the OpenAI check as a models/connectivity check, clearly labelled. It
  does not consume generation tokens. Cancellation still propagates.
- No production SSH or GitHub Actions runtime health checks are used.

## Verification

22 focused tests passed with no failures or skips: provider (3), decoder (2),
health probe (4), fallback (5), external history recorder (8). Production rollout
results will be recorded after execution. These changes improve correctness
and diagnosis; the previous timeouts are not claimed resolved by the code change.
