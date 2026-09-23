# Personalized public feed backend verification — 2026-09-22

Historical result for the September 22 pre-integration implementation. The September 23
integration uses V120 on the newer backend; see the separate
[merged-backend verification](2026-09-23-personalized-feed-backend-integration.md).
The V99 results below remain the record of that earlier run.

Verified locally against the current implementation. No production server, deployment,
App Store release, or chart position was changed or verified by these checks.

## Focused application and persistence checks

From `backend/`:

```sh
./gradlew :application:test --tests '*CommunityServiceTest' --tests '*TopicSubscriptionServiceTest' :infra:test --tests '*QuestionRepositoryLikedPageTest' --tests '*CommunityWebAdapterTest' --tests '*TopicSubscriptionWebAdapterTest' --console=plain
```

Result: **40 tests passed** (24 application, 16 infrastructure). Coverage includes:

- Topic subscriptions belong to the authenticated account; replacement/unsubscribe
  preserves other accounts. Anonymous devices cannot read or write them.
- Case/separator/Unicode whitespace normalization, display-label preservation,
  duplicate removal, 30-topic and 120-character bounds, and null/control/blank/overlong
  validation occur before mutation. Lowercase expansion is bounded too.
- Recommended ordering promotes subscribed topics, then ranks by views, likes, and
  freshness. Explicit latest/views/likes ordering has deterministic tie breaks.
- SQL applies visibility, following scope, localized search, counts, and exact offsets
  before pagination. Empty following and anonymous discovery behave as specified.
- Existing advertisement placement and paid/unknown-entitlement exclusion remain intact.
- Web adapters propagate sort/scope and authenticated identity; malformed JSON null
  elements are validation errors without modifying existing subscriptions.

## Actual MySQL and HTTP checks

Docker's daemon did not answer ping/info/ps. An existing local MySQL **8.0.32** binary
was instead started with a new disposable datadir on **127.0.0.1:33079**. A separate
Redis instance used **127.0.0.1:36379** with persistence disabled. Existing application
ports 3306/6379 and their data were untouched. Flyway migrated the isolated schema
through **V99**. The owned test services and temporary datadir were removed afterward.

```sh
BUDDYSTUDY_TEST_MYSQL_PORT=33079 REDIS_HOST=127.0.0.1 REDIS_PORT=36379 ./gradlew :tutor:test --tests '*PublicQuestionShareIntegrationTest' --tests '*PersonalizedPublicFeedIntegrationTest' --tests '*SecurityIntegrationTest' --tests '*AccountDeletionPersistenceAdapterTest' -x :tutor:processTestAot --console=plain
```

Result: **20 tests passed**, with zero XML-reported failures/errors:

| Suite | Tests | Evidence |
| --- | ---: | --- |
| `PersonalizedPublicFeedIntegrationTest` | 1 | Actual MySQL rank, exact offsets/counts, missing counters, canonical/localized Unicode topic matching, requested-language search, following/unsubscribe, and private/deleted/ungraded/blank-answer/blocked-author/author-opt-out exclusions. |
| `SecurityIntegrationTest` | 16 | Existing auth boundary regressions plus subscription GET 401 for missing/invalid tokens, anonymous-device 403, authenticated GET/PUT persistence, null/missing list and null-element 422 retaining prior data, and empty-list unsubscribe. |
| `AccountDeletionPersistenceAdapterTest` | 1 | Topic subscriptions disappear during the initial withdrawal transaction; existing asynchronous account cleanup remains idempotent. |
| `PublicQuestionShareIntegrationTest` | 2 | Anonymous `/questions/{id}` HTTP 200, localized preview and original-language fallback, escaped HTML, absence of answer/feedback/author email/name, no-store/CSP headers, and additive `/questions/*` AASA. Private/deleted/ungraded/blank-answer states return the same 404 body as missing; subsequent record privatization or author opt-out takes effect immediately. |

The real HTTP checks found and fixed a Spring Boot 4/Jackson 3 request-constructor
problem that the Jackson 2 unit mapper did not expose. The final request DTO uses a
bean-compatible default constructor and rejects omitted/null topic lists explicitly.

To rerun without Docker, create a fresh isolated local MySQL instance and the disposable
`buddystudy_test` database first. `BUDDYSTUDY_TEST_MYSQL_PORT` opts the test fixture into
only `127.0.0.1`, that fixed database, and local root with an empty password. Port 3306
is rejected; do not target an existing application database. Supply a separate Redis
port as above. Omitting the opt-in retains the normal MySQL 8.4 Testcontainers path.
`-x :tutor:processTestAot` skips unrelated AOT test-context generation for these JVM
checks. Stop the isolated services and remove their disposable data after the run.

`git diff --check -- backend` also passed. These results prove local backend behavior;
they do not replace iOS/device verification or a production rollout.
