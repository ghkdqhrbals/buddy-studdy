# Public feed and Push Admin verification — 2026-09-27

## Scope

Home removes user-selected recommendation/newest/view/like sorting while
preserving public access, search, topic following and server order. The v2
backend accepts legacy sort values but ignores them. Push Admin adds reviewed
campaign drafts, recipient snapshots, destinations, durable dispatch and
separate explicit push/inbox click tracking. No deployment or production
campaign is part of this verification.

## iOS

Required generic build passed:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData CODE_SIGNING_ALLOWED=NO build
```

Ten focused tests across `QuestionGenerationFlowTests` and
`ArchitecturePolicyTests` ran on the paired physical **iPhone 16 Pro, iOS
26.6.2** after the final MARKETING-popup change: 10 passed, 0 failures. They exercise public response order,
omitted client sort, search/scope pagination, subscription account isolation,
PUSH/INBOX request bodies, marketing/administrative popup allowlisting,
passive/read-all exclusion, and tracking failure
without navigation or draft loss. These use controlled backend responses; they
are not evidence of live APNs delivery.

Result bundle:
`build/iOSDeviceDerivedData/Logs/Test/Test-StudyMateiOS-2026.09.27_18-55-28-+0900.xcresult`.
Logs: `/tmp/buddystudy-feed-ios-build-marketing.log` and
`/tmp/buddystudy-feed-ios-marketing-device-tests.log`.

Current simulator build and Korean/English visual inspection passed. Search,
All/Following scope and interest management remain visible; no sort control or
recommendation caption remains. Fixture order preserves State/@Binding →
async/await → Observation even though views are 142 → 96 → 211. Captures:
`artifacts/server-feed-2026-09-27/feed-ko.png` and `feed-en.png`.
The simulator build used `SDK_STAT_CACHE_ENABLE=NO` after a stale SDK-cache
process stalled; generic/device checks used the normal configuration. No scheme
or macOS app changes were made.

## Backend and dashboard

`./gradlew :infra:test --tests '*CommunityWebAdapterTest' --console=plain`
passed: 5 tests, covering ignored legacy sorting and preserved search/scope/
pagination, anonymous access, liked collection authentication and advertisement
serialization order.

Dashboard `npm test` builds the committed public bundle before tests. The
campaign model and existing dashboard contract suite passed (131 tests),
including preservation of Markdown whitespace and the raw message-length limit.
Local browser QA with a fully mocked API passed at 1440×1000 and 390×844:
preview failure and retry, selected recipient/public-question landing, editing
invalidating preview, a lost save response retried with the same UUID, no send
on save, and one explicit send. No console errors or production API requests.
Screenshots: `/tmp/buddystudy-push-desktop.png`, `-preview.png`, `-mobile.png`,
and `-mobile-preview.png`.

The final targeted backend run passed **22 application notification tests, 6
infra campaign/persistence/authentication tests, and 2 MySQL integration tests**:

```sh
BUDDYSTUDY_TEST_MYSQL_PORT=33382 REDIS_HOST=127.0.0.1 REDIS_PORT=36382 ./gradlew :application:test --tests 'com.buddystudy.backend.notification.*' :infra:test --tests '*PushCampaign*' :tutor:test --tests '*PushCampaignTransactionIntegrationTest' --tests '*AccountDeletionPersistenceAdapterTest' -x :tutor:processTestAot --console=plain
```

The MySQL tests used a temporary, isolated MySQL 8.0.32 data directory and Redis
instance. They verified a fresh migration through V102, concurrent same-key
creation and send, transactional rollback of recipients/outbox state, retry
without duplicate dispatch, ownership and click deduplication, and preservation
through read-all and notification deletion. Account withdrawal retained the
historical 1/2 CTR while removing recipient identities and scrubbing selected
user IDs from sent campaigns and drafts. No production database or push
provider was used. The temporary daemons were stopped after verification.
Test reports are under `backend/{application,infra,tutor}/build/reports/tests/test/`.
Docker was unavailable, so the test fixture used the isolated local MySQL
fallback; JVM tests excluded Spring test AOT context generation.

## Store discovery

Read-only App Store Connect inspection confirmed current iOS **1.2.0 (122)**,
Ready for Distribution, primary Education and secondary Productivity. The live
English name, subtitle and keyword list already match the discovery metadata;
the live description and feed screenshots still describe the old sorting UI.

The June 28–September 25 first-time-download report shows 1 download on September
17. Other days show `-`; retention and crash overview report insufficient data.
No ranking gain, acquisition lift or useful retention estimate is established.

Both local validators passed for Korean, English and Japanese:

```sh
APP_STORE_METADATA_VALIDATE_ONLY=1 ruby scripts/update-app-store-metadata.rb
APP_STORE_METADATA_VALIDATE_ONLY=1 ruby scripts/update-app-store-app-info.rb
```

The next-release feed description and release notes were corrected in source.
The [featuring nomination](../../app-store/metadata/featuring-nomination.md) is
a local draft with no invented launch date. No App Store metadata, nomination,
product-page experiment, or release was published in this task.

## Verification limits

- Live APNs acceptance → device alert → cold/warm tap → backend CTR still needs
  a staging campaign after deployment. No customer received a test push.
- The open endpoint verifies ownership and deduplicates events. Reporting is
  best effort and old clients do not report opens, so CTR can undercount.
- Existing payloads contain no account identity for client-side routing. An
  old-account Home-message push arriving after switching accounts can still
  display its payload; server ownership prevents attributing its click to the
  new account. That pre-existing routing behavior was not expanded here.
- User changes to the Xcode scheme, IDE files and unrelated screenshot assets
  were preserved and excluded from the implementation commit.
