# BuddyStuddy Backend Hexagonal Architecture

## Context

BuddyStuddy backend is a Spring Boot Kotlin service that owns device registration, authentication, study settings, question generation, grading, records, public questions, reports, statistics, APNs push, OpenAI calls, PostgreSQL persistence, and Redis stream coordination.

The backend is now grouped by domain packages such as `auth`, `study`, `community`, and `settings`, with web/scheduler/stream handlers as inbound adapters, JPA/OpenAI/APNs/Redis integrations as outbound adapters, and use cases behind application ports.

## Goals

- Keep API behavior stable while changing internal structure.
- Make use cases testable without Spring, PostgreSQL, APNs, OpenAI, or Redis.
- Separate incoming adapters, application ports, application services, domain model, and outgoing adapters.
- Keep each feature domain readable: `auth`, `study`, `community`, `profile`, `settings`, `admin`.
- Avoid a large-bang rewrite; migrate one use-case group at a time.

## Non-Goals

- Changing API paths or response contracts.
- Replacing JPA, PostgreSQL, Redis stream coordinator, APNs, or OpenAI.
- Introducing CQRS or event sourcing for all domain state.
- Moving iOS client logic in this refactor.

## Current Architecture

Use package-by-feature hexagonal architecture:

```text
com.buddystuddy.backend
  common
    adapter
      inbound.web
    application
      error
      service
  auth
    adapter
      inbound.web
      outbound.persistence
    application
      port.inbound
      port.outbound
      service
  study
    adapter
      inbound.web
      inbound.scheduler
      inbound.stream
      outbound.persistence
      outbound.openai
      outbound.apns
      outbound.stream
    application
      port.inbound
      port.outbound
      service
community
  adapter
    inbound.web
    inbound.stream
    outbound.persistence
  application
    port.inbound
    port.outbound
    service
```

Dependency rule:

```text
adapter -> application -> domain
```

Adapters may depend on Spring, JPA, APNs, Redis, OpenAI, HTTP DTOs, and external SDKs. Application services depend on inbound ports, outbound ports, common application errors, DTO/result models, and domain/JPA entity bridge types. Application services must not import `*.adapter.*`.

## Component Responsibilities

### Incoming Adapters

- Web controllers under `adapter.inbound.web`.
- Scheduler entrypoints under `adapter.inbound.scheduler`.
- Redis stream listeners under `adapter.inbound.stream`.
- Translate HTTP/event payloads into use-case commands.
- Call inbound ports only.
- Convert use-case results into API DTOs.

Example:

```kotlin
class StudyController(
    private val createQuestionUseCase: CreateQuestionUseCase,
)
```

### Inbound Ports

Inbound ports define user/system actions.

Examples:

```kotlin
interface RegisterDeviceUseCase {
    fun register(command: RegisterDeviceCommand): RegisteredDevice
}

interface CreateQuestionUseCase {
    fun create(command: CreateQuestionCommand): StudyRecord
}

interface GradeAnswerUseCase {
    fun grade(command: GradeAnswerCommand): StudyRecord
}

interface BrowsePublicQuestionsUseCase {
    fun browse(query: PublicQuestionQuery): PublicQuestionPage
}
```

### Application Services

- Implement inbound ports.
- Hold transaction boundaries.
- Enforce use-case policies: pending question cap, public visibility rules, login/session rules, one pending question per study room.
- Depend on outbound ports, not JPA repositories directly.

Example:

```kotlin
class CreateQuestionService(
    private val schedulePort: LoadSchedulePort,
    private val questionPort: SaveQuestionPort,
    private val openAiPort: GenerateQuestionPort,
    private val statsPort: SaveQuestionStatsPort,
) : CreateQuestionUseCase
```

### Outbound Ports

Outbound ports describe what the application needs from outside.

Examples:

```kotlin
interface LoadUserPort
interface SaveUserPort
interface LoadDevicePort
interface SaveDevicePort
interface LoadSchedulePort
interface SaveSchedulePort
interface LoadQuestionPort
interface SaveQuestionPort
interface GenerateQuestionPort
interface GradeAnswerPort
interface PublishPushRequestPort
interface PublicQuestionReactionPublishPort
interface SendReportEmailPort
```

### Outgoing Adapters

- JPA repositories and persistence mappers.
- OpenAI client adapter.
- APNs adapter.
- Redis stream coordinator adapter.
- SMTP/report email adapter.
- AWS secrets adapter.

Outgoing adapters implement outbound ports.

## Recommended Package Layout

For `study`:

```text
study
  domain
    StudyRoom.kt
    Question.kt
    Grading.kt
    StudyPolicy.kt
  application
    port.inbound
      CreateQuestionUseCase.kt
      GradeAnswerUseCase.kt
      BrowseRecordsUseCase.kt
      GetStatsUseCase.kt
    port.outbound
      LoadSchedulePort.kt
      SaveSchedulePort.kt
      LoadQuestionPort.kt
      SaveQuestionPort.kt
      GenerateQuestionPort.kt
      GradeAnswerPort.kt
      PublishPushRequestPort.kt
    service
      CreateQuestionService.kt
      GradeAnswerService.kt
      RecordsService.kt
      StudyStatsService.kt
  adapter
    inbound.web
      StudyController.kt
    inbound.scheduler
      QuestionScheduler.kt
    outbound.persistence
      JpaQuestionRepository.kt
      JpaScheduleRepository.kt
      StudyPersistenceAdapter.kt
      StudyPersistenceMapper.kt
    outbound.openai
      OpenAIQuestionAdapter.kt
    outbound.stream
      RedisQuestionPushAdapter.kt
```

For `auth`:

```text
auth
  domain
    User.kt
    Device.kt
    Session.kt
    UserStatus.kt
    AuthProvider.kt
  application
    port.inbound
      RegisterDeviceUseCase.kt
      LoginWithDeviceUseCase.kt
      LoginWithGoogleUseCase.kt
      LoginWithEmailUseCase.kt
      RefreshPrincipalUseCase.kt
    port.outbound
      LoadUserPort.kt
      SaveUserPort.kt
      LoadDevicePort.kt
      SaveDevicePort.kt
      VerifyGoogleTokenPort.kt
  adapter
    inbound.web
      AuthController.kt
    outbound.persistence
      UserRepository.kt
      DeviceRepository.kt
      UserDeviceRepository.kt
```

## Data Model

JPA entities are currently centralized under `domain/Entities.kt` as a bridge. The active dependency boundary is enforced by ports: application services depend on outbound port interfaces, and Spring Data repositories in outbound persistence adapters implement those ports.

Domain root models are now used as the application-facing consistency boundary before the JPA entity bridge is fully removed:

```text
auth.domain.Account
  - User + active Device relationship
  - device attachment and push token mutation

study.domain.StudyRoom
  - Schedule + pending question count
  - pending cap invariant and question creation

study.domain.StudyRoomSettings
  - Schedule settings mutation
  - interval/model/publicity/OpenAI-key related setting updates

study.domain.StudyRecord
  - Question + QuestionStats read model
  - answer, grade, skip, publicity restriction, response projection

community.domain.PublicQuestion
  - Public answered Question + author + stats + viewer reaction
  - public question response projection
```

These domain roots intentionally wrap the current JPA entity bridge during migration. Application services should call domain behavior instead of mutating entity fields directly.

Long-term, JPA entities should move to persistence adapters as database records, not domain models:

```text
adapter.outbound.persistence.entity.UserJpaEntity
adapter.outbound.persistence.entity.QuestionJpaEntity
adapter.outbound.persistence.entity.ScheduleJpaEntity
```

Domain models should be plain Kotlin:

```kotlin
data class User(
    val id: UserId,
    val provider: AuthProvider,
    val status: UserStatus,
    val displayName: String,
)
```

Persistence mappers convert between JPA entities and domain models.

Current caveat: application services still use the entity bridge types returned by ports. This keeps the migration safe and API-compatible, while preventing direct application-to-adapter dependencies.

## API / Event Contracts

HTTP DTOs should remain in incoming web adapters:

```text
adapter.inbound.web.dto
```

Redis stream event DTOs should remain in stream adapters:

```text
adapter.outbound.stream.event
adapter.inbound.stream.event
```

Application commands/results should be transport-neutral:

```kotlin
data class CreateQuestionCommand(
    val principal: Principal,
    val topic: String?,
)
```

## Consistency and Ordering

- Question creation is a single database transaction for schedule lookup, pending limit check, question insert, and stats row insert.
- Push publishing is at-least-once. Duplicate push events must be safe because the question record is already persisted.
- Like/comment/view counters are eventually consistent through Redis stream aggregation.
- Source-of-truth tables remain `questions`, `question_likes`, and `question_comments`.
- `question_stats` remains a derived read model.
- Public question reactions publish through `community.application.port.outbound.PublicQuestionReactionPublishPort`.
- The Redis stream adapter implements the reaction publish port, so community use cases do not depend on stream implementation details or study ports.

## Failure Handling

- OpenAI failure: do not create a question record unless a valid generated question exists.
- Push publish failure: question remains stored, schedule stores `lastError`, retry can publish or next cycle can recover.
- Redis stream duplicate event: aggregation adapters must be idempotent where event id exists, or monotonic enough for counters with periodic reconciliation.
- APNs failure: mark/log push delivery failure without deleting question.
- Google verification failure: return unified auth error envelope.

## Scalability

- Pending question cap queries need indexes on `device_id`, `user_id`, `topic`, `deleted_at`, `skipped_at`, `score`.
- Public question browse uses answered public rows only and should remain paginated.
- Topic stats should not load unbounded records. Current 10,000 cap is acceptable short term, but should become a repository-level grouped query when records grow.
- Reaction aggregation should consume by stream partition key `questionId`.

## Observability

- Keep request/response logs in `common.adapter.inbound.web`.
- Application services should log domain events, not raw HTTP.
- Adapters should log external dependency failures with provider, request id, and sanitized error code.
- Add metrics later:
  - login failures by error code
  - OpenAI generation latency/failure
  - scheduler due lag
  - pending question cap hits
  - Redis stream publish/consume failures
  - APNs delivery failures

## Completed Refactor

- Moved web controllers to `adapter.inbound.web`.
- Moved scheduler and stream consumers to `adapter.inbound.scheduler` / `adapter.inbound.stream`.
- Moved JPA repositories, OpenAI client, APNs client, and Redis stream publisher to `adapter.outbound`.
- Added inbound ports for auth, admin, profile, settings, study, and community.
- Added outbound persistence ports for auth, study, and community.
- Added outbound OpenAI and question engagement event ports.
- Moved API exception types to `common.application.error`.
- Verified that application packages no longer import `*.adapter.*`.
- Moved HTTP request DTOs into domain-specific `adapter.inbound.web.dto` packages.
- Moved use-case response models and entity-to-response mappers into domain-specific `application.model` packages.
- Replaced application-layer web request dependencies with transport-neutral inbound command types.
- Introduced domain root models for auth account/device ownership, study room settings, study question lifecycle, and public question projection.
- Moved question lifecycle mutations such as answer, grade, skip, and publicity restriction behind domain methods.

## Remaining Hardening

1. Move JPA entity classes into outbound persistence packages after all mappers are introduced.
2. Replace entity bridge returns with pure domain models.
3. Reduce `BackendSupportService` by moving helper behavior into domain-specific services.
4. Convert `StatsService` to a study application use case backed by stats-specific persistence ports.
5. Continue moving response mapping out of application services as more transports or API versions are introduced.

## Test Plan

- Application service unit tests with fake ports:
  - no Spring context
  - no database
  - exact policy tests
- Adapter integration tests:
  - web controller request/response
  - JPA persistence adapter
  - Redis stream adapter
  - OpenAI adapter mocked by HTTP stub
- End-to-end Spring tests:
  - device register -> token -> login -> settings -> create question -> grade -> public question browse
- Scheduler tests:
  - due schedule creates max one pending question per study room
  - missing APNs token defers without generating undeliverable push
  - pending limit skips generation

## Tradeoffs

- Hexagonal structure adds more files and interfaces.
- The payoff is higher only if application services stop importing JPA repositories and external clients directly.
- Keeping JPA entities temporarily in `domain` reduces migration risk, but it is not the final clean architecture.
- Use-case ports should not be created mechanically for every tiny method. Create ports around real dependency boundaries.

## Risks and Open Questions

- API response models are currently treated as application result models for compatibility with existing controllers. Domain roots expose projections, and application mappers convert those projections into response models.
- Domain roots still wrap JPA entities as a migration bridge. This keeps behavior safe but is not the final pure DDD model.
- `StatsService` currently calculates from loaded records. It should move behind a stats use case and later use grouped repository queries.
- `BackendSupportService` is useful during migration but should shrink as domain-specific ports mature.
- Account withdrawal and clear-record behavior need explicit product policy before moving into final use cases.
