# Backend growth integration verification — 2026-09-23

Verified locally in the separate `app-store-growth-integration/study-mate` worktree,
starting from `origin/main` `1d57ac12`, with growth integration `18eb97df` and the
production billing/translation branch merge `24fb3b68`, plus the final fixes described
below. The original worktree was not changed. These checks did not deploy anything,
connect to a production database, verify App Store release status, or establish chart
placement.

## Integration and preserved behavior

- The new subscription migration is **V120__community_topic_subscriptions.sql**.
  Existing V99 belongs to Voice Tutor. Previously deployed migrations were not edited.
- V2 feed and search retain `recommended|latest|views|likes` sorting and
  `all|following` scope, defaulting to `recommended`/`all`. Ranking, visibility,
  matching, counts, and exact offsets run in SQL before pagination. Recorded views
  are event counts, not unique-reader counts.
- Latest canonical record eligibility remains intact: `QUESTION` is graded with a
  nonblank answer; `VOICE_TUTOR` is completed with a nonblank question/answer and an
  existing voice extension owned by the canonical record's author. Private, deleted,
  blocked-author, and author-opt-out records remain excluded where applicable.
  Voice ownership, deletion, pending-question, grading/watchdog, rollback, and embedding
  regressions in the upstream repository tests were retained.
- Latest native-ad-slot reservation, entitlement exclusion, first-page placement,
  lazy fallback, and typed record/localization projection behavior were preserved.
  Organic ranking does not use purchases or memberships.
- Share preview URLs use **numeric canonical `questions.id`**, including voice
  records, never the internal voice extension ID. The preview reuses the public
  completion condition, reads only topic/question/language, and does not increment
  views or enqueue translations. Ordinary questions use available translations;
  voice previews show canonical source text and its source language because voice
  search rows can contain original fallback text under a requested-language key.
- Subscription PUT locks the account and rechecks `ACTIVE` inside the same
  transaction. A request authenticated before withdrawal cannot recreate private
  interests after withdrawal wins that row lock. Withdrawal deletes interests in
  its initial transaction; existing asynchronous cleanup remains idempotent.
- Following compares subscription display labels and canonical/localized topic labels
  using the same SQL lowercase/separator normalization. This avoids JVM-versus-MySQL
  casing differences for a subscribed original label such as dotted `İ` or Greek
  `ΟΣ`. Explicit MySQL `COLLATE utf8mb4_bin` retains accent-sensitive equality:
  `Café` does not match `Cafe`. Stored `topic_key` still controls replacement
  deduplication. H2 omits the unsupported collation expression and already compares
  accents distinctly; actual MySQL tests exercise the production expression.

## Results

**199 selected tests passed, zero failures/errors/skips:**

| Module/suite group | Tests | Coverage |
| --- | ---: | --- |
| Application | 84 | Community/native slot behavior, subscription validation, share service, billing/RevenueCat/admin billing regressions. |
| Infrastructure | 53 | Ranking and exact pages, latest voice visibility/deletion invariants, subscription/web adapters, share HTML/projection, AASA, translation decoder/provider/probe, billing lifecycle metrics. |
| `PersonalizedPublicFeedIntegrationTest` | 3 | Real MySQL ranking, counters, canonical/localized Unicode labels, exact offsets, follow/unfollow, all visibility exclusions, canonical voice ownership/deletion, dotted-I/Greek round trips, distinct accents. |
| `PublicQuestionShareIntegrationTest` | 3 | Anonymous HTML, localization/source fallback, escaping, minimal projection, no-store/CSP, generic invisible-state 404, canonical voice/source language/extension ownership, additive AASA. |
| `SecurityIntegrationTest` | 16 | Existing auth boundaries plus subscription 401/403, authenticated GET/PUT, null/missing list/null element 422 without mutation, unsubscribe. |
| `AccountDeletionPersistenceAdapterTest` | 3 | Active-account permission seed, immediate interest removal and idempotent cleanup, stale authenticated PUT rejected after withdrawal. |
| `BillingLedgerPersistenceAdapterTest` | 37 | Real MySQL production billing branch regression suite. |

From `backend/`, the selected JVM suite commands are:

```sh
./gradlew :application:test --tests '*CommunityServiceTest' --tests '*TopicSubscriptionServiceTest' --tests '*PublicQuestionShareServiceTest' --tests '*BillingServiceTest' :infra:test --tests '*QuestionRepositoryLikedPageTest' --tests '*CommunityWebAdapterTest' --tests '*TopicSubscriptionWebAdapterTest' --tests '*PublicQuestionShare*Test' --tests '*ReferralPublicControllerTest' --tests '*BillingLifecycleMetricsReporterTest' --tests '*LibreTranslate*Test' --tests '*AdminTranslationProviderHealthProbeTest' --console=plain --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx2g

BUDDYSTUDY_TEST_MYSQL_PORT=33079 REDIS_HOST=127.0.0.1 REDIS_PORT=36379 ./gradlew :tutor:test --tests '*PublicQuestionShareIntegrationTest' --tests '*PersonalizedPublicFeedIntegrationTest' --tests '*SecurityIntegrationTest' --tests '*AccountDeletionPersistenceAdapterTest' --tests '*BillingLedgerPersistenceAdapterTest' -x :tutor:processTestAot --console=plain --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx2g
```

The infrastructure selection was rerun with the MySQL selection after the final SQL
normalization change. XML results under each module's `build/test-results/test`
were counted, including Gradle's shortened filenames for long suite names.
`git diff --check` passed.

The first clean integration compile exceeded Kotlin's default compiler heap. The
command-line `-Pkotlin.daemon.jvmargs=-Xmx2g --max-workers=2` settings resolved it;
no repository-wide build-memory setting was changed.

## Existing billing test expectation corrected

The expanded selection found one pre-existing failing assertion in
`completed upgrade repairs a stale product projection without regressing newer lifecycle state`:
expected quota 300, actual 1000. The same failure was reproduced from an isolated
`git archive bfd1c9a8 backend` baseline with unchanged production code. Only the
local MySQL test-connection fixture was copied in so the baseline could use the
isolated engine without Docker.

That test deliberately corrupts the entitlement projection to TIER2 but retains a
valid TIER3 membership. `activePlanForUser` selects the higher valid plan, consistent
with [billing quota rules](../BILLING.md). The assertion now expects 1000 and explains
why. The surrounding entitlement-repair, newer cancellation-state, and final quota
assertions remain. **No production billing logic was changed.** The full 37-test
billing persistence selection then passed.

## Isolated runtime and rerun

The actual database was local **MySQL 8.0.32**, not a MySQL mock. It used a newly
initialized disposable datadir, loopback **33079**, and only the disposable
`buddystudy_test` schema. Flyway applied the latest history through **V120**.
Redis **8.10.1** used loopback **36379**, `save ""`, and `appendonly no`.
Existing application ports 3306/6379 and their data were untouched.

To rerun, create a fresh isolated local MySQL instance and `buddystudy_test` first,
then start a separate disposable Redis instance and use the command above.
`BUDDYSTUDY_TEST_MYSQL_PORT` accepts only a nondefault local port; host, database,
user and password are fixed by the test fixture to `127.0.0.1`, `buddystudy_test`,
`root`, and an empty password. It rejects 3306. Without this opt-in, the existing
MySQL 8.4 Testcontainers path remains the default. This run verifies 8.0.32 directly;
it does not claim a Docker/MySQL 8.4 run. `-x :tutor:processTestAot` skips unrelated
AOT context generation for these focused JVM tests.

After verification, both owned services were stopped and their disposable datadir,
Unix socket, and the baseline archive/build were removed. Production rollout and
real-device iOS verification are separate checks.
