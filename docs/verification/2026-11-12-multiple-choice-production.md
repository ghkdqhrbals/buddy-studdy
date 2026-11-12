# Multiple-choice production integration — 2026-11-12

## Compatibility and source

The feature was ported onto current remote `main` (`ded6e07f`), preserving the
existing Voice Tutor, curriculum, canonical record identity and ownership paths.
The original checkout and its unrelated edits remain intact.

iOS 1.3.1 negotiates `X-BuddyStudy-API-Version: 3`; absent headers retain contract
1. Old clients generate free text, browse public pages whose counts/pagination
exclude objective questions, and can answer an owned pending objective question
through a numbered-text fallback. Existing MCP callers are not silently opted
into contract 3. Current device capability can downgrade, and scheduled objective
delivery requires every active owned delivery device to be capable. No forced
update policy is activated.

Both Flyway lineages append V126–V129 rather than colliding with deployed
versions. The backend-only feature flag is validated and passed into runtime
configuration by the deploy repository; its workflow remains configuration-only,
without runtime probes, health gates or a monitoring rollout.

## Validation

- Domain: 43 tests, no failures.
- Application: 781 tests, no failures.
- Infrastructure: 1,766 tests, no failures; 6 existing skips.
- MySQL: 47 focused integration cases passed across the final runs. This includes
  real legacy/v3 HTTP reads of the same pending question, full-width numeric legacy
  submission, immutable choice, public visibility, quota, transaction, deletion,
  migration and device ownership behavior. The historical V79 test pins its JDBC
  connection to UTC so an expired test session stays expired on an Asia/Seoul host.
- Latest unsigned generic iOS build passed using `StudyMateiOS` only.
- Connected iPhone: 520 checks passed, 17 opt-in checks skipped. Five existing
  hosted Voice UI cases were excluded because this test host lacked a foreground
  scene; their functions are unchanged from remote `main`. The exercised suites
  cover objective questions, API contract registration, external-upload header
  isolation, common voice records, study records, voice drafts and contracts.
- Release-selection policy: 3 tests / 6 assertions passed; TestFlight notes:
  13 tests / 46 assertions passed; Ruby syntax check passed.
- Deployment workflow: 9 static tests passed, YAML parsed, application template
  and deploy workflow match. No production health/smoke check was added to Actions.

The backend unit and integration commands use one Gradle worker and a 6 GiB
in-process Kotlin compilation heap, matching the existing image build policy.
JVM integration runs skip unrelated `:tutor:processTestAot` generation.

## Rollout record

Deployment configuration merged in
[personal-deploy PR 23](https://github.com/ghkdqhrbals/personal-deploy/pull/23),
commit `7a692592`. The generation flag was explicitly initialized to `false`.

App Store Connect iOS 1.3.1 draft `72fd6a81-e823-4e3d-85ce-94ae26c4d88a` is
prepared with `AFTER_APPROVAL`. Only Korean, English and Japanese `whatsNew`
changed; inherited public metadata, all 10 screenshot sets / 60 complete images,
and review contact/demo credentials were preserved.

Backend deployment, exact release build and feature activation run identifiers
are recorded after their workflows complete. No old-version forced-update
campaign is part of this rollout.
