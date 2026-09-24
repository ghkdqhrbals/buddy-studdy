# Follow-up and custom questions — iOS 1.3.0 verification

## Implemented behavior

- An owned, graded question can continue with up to two AI follow-ups. Each
  consumes one question allowance, uses the original answer/feedback as context,
  stays private, and keeps its original topic and difficulty.
- The existing durable generation Saga handles quota reservation, idempotent
  replay, completion and failure compensation. Replaying the final available
  allowance succeeds without reserving a second unit.
- A skipped follow-up remains read-only in its thread and ends that continuation.
  Skipping, deleting or reopening earlier turns cannot bypass the thread limit.
- Learners can write and save both a custom question and answer from the study
  room's More menu. The record shows `사용자 생성 질문` / `Custom question`, without
  a score, grading, AI call or generation allowance charge.
- Custom drafts use SettingsStore through the existing repository/use-case path,
  scoped to the account and study. Save retries retain their idempotency key;
  successful saves preserve the existing unanswered AI question and answer draft.
- Follow-ups and custom records are excluded from ordinary score, ability and
  growth calculations. Completed custom records remain in paginated history.
- The iOS app's Debug and Release marketing versions are 1.3.0. No macOS target,
  release version, build or test was changed or run.

See [the feature contract](../FOLLOW_UP_QUESTIONS.md).

## Backend verification

**102 tests passed, zero failures**:

| Scope | Tests | Evidence |
| --- | ---: | --- |
| Domain | 21 | Existing domain tests; unchanged results reused by Gradle |
| Application | 39 | Follow-up prompt/request/thread, custom save, generation request/execution/process/rollback, record mapping |
| Infrastructure | 13 | H2 thread persistence and paginated/custom record queries |
| Tutor | 29 | 17 HTTP tests, 4 MySQL Saga tests, 8 native runtime-hint tests |

```sh
cd backend
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.2+10.1/Contents/Home \
BUDDYSTUDY_TEST_MYSQL_PORT=33361 REDIS_HOST=127.0.0.1 REDIS_PORT=36381 \
./gradlew :domain:test \
  :application:test --tests '*FollowUpQuestion*' --tests '*CustomQuestionServiceTest' \
  --tests '*QuestionGeneration*' --tests '*StudyRecordMappersTest' \
  :infra:test --tests '*QuestionThreadPersistenceTest' --tests '*QuestionRepositoryLikedPageTest' \
  :tutor:test --tests '*StudyApiIntegrationTest' \
  --tests '*QuestionGenerationPersistenceIntegrationTest' --tests '*ApplicationRuntimeHintsTest' \
  -x :tutor:processTestAot --console=plain
```

The HTTP/Saga tests used a fresh, disposable **real MySQL 8.0.32** database on
localhost port 33361 and isolated Redis on port 36381, using the repository's
existing local integration-test fallback. The schema used `utf8mb4_0900_ai_ci`,
matching the existing stats table. No production server or user's default local
database was used. Earlier attempts exposed an incorrectly configured temporary
database collation and leftover fixed test IDs; recreating this disposable
database resolved both. The final run was `BUILD SUCCESSFUL`.
Both isolated test servers were shut down after verification.

Covered contracts include concurrent same/different idempotency keys, ownership,
two-turn limits, skipped/deleted context, last-unit replay, private persistence,
coached-practice statistics exclusion, custom validation, unchanged authored
language, missing score, unchanged quota, pagination, and pending AI preservation.
OpenAI responses are mocked; these tests do not call the live model.

Local log: `/tmp/buddystudy130-backend-final.log`. JUnit XML is under each
module's `build/test-results/test/`; machine-local logs are not committed.

## iOS verification

The required generic iOS device build **succeeded**:

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath build/iOSDeviceDerivedData \
  CODE_SIGNING_ALLOWED=NO build
```

The selected iOS simulator suites **passed 264 tests, zero failures**:
149 architecture policy tests and 115 question-generation/session/record tests,
including 23 new regressions for the follow-up/custom flows and record identity.

```sh
xcodebuild -project StudyMate.xcodeproj -scheme StudyMateiOS -configuration Debug \
  -destination 'platform=iOS Simulator,id=B4AC92AC-FF55-495C-B4C3-079FAE6A880A' \
  -derivedDataPath build/iOS130SimulatorDerivedData -parallel-testing-enabled NO \
  -only-testing:StudyMateiOSTests/QuestionGenerationFlowTests \
  -only-testing:StudyMateiOSTests/ArchitecturePolicyTests test
```

The final rerun includes transient-generation retry recovery with the same key,
account/draft isolation, skipped-thread presentation, strict identity when
timestamps or question wording repeat, and an absent weekly average when there
are no independent scores. A genuine zero score remains zero. Related source
policy assertions were updated for the new error policy and own-record routing;
the pre-existing Home loading assertion was scoped to its component instead of
assuming two unrelated source lines were adjacent.

Local logs: `/tmp/buddystudy130-ios-build-final.log` and
`/tmp/buddystudy130-ios-tests-final.log`. The device build emitted the existing
App Intents metadata warning because the app does not use AppIntents.framework.
Only the `StudyMateiOS` scheme was used. `git diff --check` passed.

The dedicated iPhone 16 Pro simulator runs iOS 26.0. Synthetic Debug-only fixtures
show the original and follow-up conversation and authored records. UI inspection
confirmed the More-menu composer, separate question/answer fields, private and
no-grading explanation, record/detail custom tags, and restoration of both text
fields after cancelling and reopening the composer. The authored record has no
numeric score, answer editor or follow-up action.

Screenshot evidence includes the [restored question/answer draft](assets/ios-1.3.0/custom-composer-draft-ko.png),
[custom record detail](assets/ios-1.3.0/custom-record-detail-ko.png), and
[record list with no synthetic zero average](assets/ios-1.3.0/custom-records-ko.png).
Additional captures are under `/tmp/buddystudy130-qa/`. These fixtures do not
exercise live production APIs. Saving, idempotency and draft-preservation
behavior are covered separately by the iOS regressions and MySQL HTTP tests.

The registered **Min iPhone (iPhone 16 Pro)** is currently reported as
`unavailable` by `xcrun devicectl list devices`. Physical-device validation is
still required; simulator checks are not represented as a physical-device pass.

## Release and advertising status

No backend deployment, App Store Connect upload/submission or public release
was performed. Deploy backend migrations V100/V101 with their API implementation
through the existing GitHub Actions path before releasing the 1.3.0 client.
H2 test equivalents are V71/V72. Custom records retain the existing terminal
`GRADED` wire status for old-client decoding compatibility, while source
`custom_question` and null grading fields identify ungraded authored content.

Current source does **not** integrate AdMob. Commit `57972f96` removed it;
release checks and iOS architecture tests reject Google Mobile Ads / UMP
dependencies and AdMob IDs. Advertising continues through the existing
BuddyStudy/Coupang server-selected campaigns. This is a source audit, not an
inspection of the published App Store binary.
