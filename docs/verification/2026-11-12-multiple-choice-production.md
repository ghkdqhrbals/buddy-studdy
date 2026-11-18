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

Implementation merged in
[BuddyStudy PR 32](https://github.com/ghkdqhrbals/buddy-studdy/pull/32), source
`4d2af6465c66e2145ba34a99dc9f9787c51a3aff`. The immutable iOS candidate tag is
`candidate/ios-1.3.1-mc-20261112`.

[Backend image build 43201603254](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/43201603254)
succeeded on a GitHub-hosted ARM64 runner. The deployed reference is
`ghcr.io/ghkdqhrbals/buddystudy-backend:4d2af6465c66e2145ba34a99dc9f9787c51a3aff-jvm@sha256:d6c7a1765bc368a1919f3c240dcc23256dcceafd8b6d56b90c080b4ca48651a1`.

[Preparation deployment 43202732809](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/43202732809)
succeeded with generation disabled. This backend-only workflow submitted the
exact image and did not build code on EC2 or deploy monitoring. A manual,
read-only public search request at 10:50 UTC returned HTTP 200 with response
contract `1` when the request header was absent, and HTTP 200 with contract `3`
when `X-BuddyStudy-API-Version: 3` was supplied. Both responses included
`Vary: X-BuddyStudy-API-Version`. This was not an Actions runtime health gate and
did not generate questions, consume quota or submit answers.

[iOS release 43201608200](https://github.com/ghkdqhrbals/buddy-studdy/actions/runs/43201608200)
succeeded for the same source, including generic Debug validation, signed Release
archive, entitlement validation, IPA export and App Store Connect upload.
At 10:55 UTC, App Store Connect reported iOS **1.3.1 (128)** as `VALID`, build ID
`da847471-a95b-4b84-88de-e5745e6b991f`, and the 1.3.1 App Store version's build
relationship pointed to that exact binary. Korean, English and Japanese
TestFlight testing notes matched the candidate source.

After the supported binary was uploaded and selected, the deployment repository
variable `BUDDYSTUDY_MULTIPLE_CHOICE_ENABLED` was changed to `true` and read back.
[Activation deployment 43204681058](https://github.com/ghkdqhrbals/personal-deploy/actions/runs/43204681058)
succeeded using the same pinned image and backend-only configuration, with
`promote_swarm=false` and notifications disabled. At 10:57 UTC, manual read-only
public search requests again returned HTTP 200 with contracts 1 and 3 respectively
and the version-negotiation `Vary` header. No live generation, vote or learning
record was created for this operational check.

The feature is enabled for capable contract-3 clients, including the uploaded
TestFlight/review binary. Public App Store availability still depends on Apple
approval. No old-version forced-update campaign is part of this rollout.

App Review submission `1d19cd39-b283-498f-b36c-0679a25751c4` contains only this
1.3.1 version and exact build 128. After submission, both the version and review
submission read back as `WAITING_FOR_REVIEW`, with `AFTER_APPROVAL` preserved.
No unrelated review items were submitted. App Store public release is pending
Apple review; server activation and TestFlight upload are complete.
