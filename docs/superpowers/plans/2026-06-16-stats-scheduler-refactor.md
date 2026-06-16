# Stats Scheduler Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve BuddyStuddy backend statistics and scheduled question generation by separating calculation, refresh, due scheduling, and backoff responsibilities without reintroducing dirty stats tables.

**Architecture:** Keep the existing `user_stats` read model and `studies.next_due_at` scheduling model. Split large service/scheduler methods into focused collaborators so the scheduling state transition is explicit and the stats response assembly is isolated from raw query orchestration.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, existing hexagonal ports/use cases, JUnit 5, AssertJ.

---

### Task 1: Stats Assembly Refactor

**Files:**
- Modify: `backend/application/src/main/kotlin/com/buddystuddy/backend/stats/StatsService.kt`
- Modify: `backend/application/src/test/kotlin/com/buddystuddy/backend/stats/UserStatsServiceTest.kt`

- [x] **Step 1: Preserve current behavior with tests**
  - Keep existing tests covering DB-paged stats lookup, latest record batching, and empty topic behavior.
  - Add a focused test that verifies the topic level range remains bounded and sample-count based.

- [x] **Step 2: Extract topic response assembly**
  - Create a `TopicStatsAssembler` class in the same file/package.
  - Move average, best score, correct rate, dominant level, and level-range calculation into it.
  - Keep `StatsService` responsible for query orchestration only.

- [x] **Step 3: Extract stats refresh row building**
  - Create a `UserStatsRowBuilder` class in the same file/package.
  - Move graded-question grouping and `UserStatsEntity` creation into it.
  - Keep `StatsRefreshService` responsible for reading graded questions and calling `userStats.syncAll`.

- [x] **Step 4: Verify**
  - Run `./gradlew test --no-daemon --tests '*UserStatsServiceTest'`.

### Task 2: Scheduling Reservation Refactor

**Files:**
- Modify: `backend/infra/src/main/kotlin/com/buddystuddy/backend/study/adapter/inbound/scheduler/QuestionScheduler.kt`
- Modify: `backend/infra/src/test/kotlin/com/buddystuddy/backend/study/adapter/inbound/scheduler/QuestionSchedulerTest.kt`

- [x] **Step 1: Preserve scheduling behavior with tests**
  - Keep existing tests for batch pending counts and user/recent lookup reuse.
  - Add tests for explicit pending backoff and OpenAI/API-key failure backoff.

- [x] **Step 2: Extract backoff policy**
  - Add `ScheduleBackoffPolicy` to calculate pending-limit backoff, missing-key backoff, and failure backoff.
  - Use longer backoff for external/API failures than pending-limit retries.

- [x] **Step 3: Extract per-study creator**
  - Add `ScheduledQuestionCreator` that handles one due study: user lookup, pending check, OpenAI generation, question save, stats save, outbox enqueue, and study state update.
  - Keep `QuestionScheduler` responsible for polling due studies, batching pending counts, and shared caches.

- [x] **Step 4: Verify**
  - Run `./gradlew test --no-daemon --tests '*QuestionSchedulerTest'`.

### Task 3: Full Backend Verification And Commit

**Files:**
- All modified backend/test/plan files.

- [x] **Step 1: Run backend tests**
  - Ran `./gradlew :application:test :infra:test --no-daemon`.
  - Ran `./gradlew test --no-daemon`; it failed in `RedisStreamListenerDefaultsTest` because pre-existing uncommitted stream prefix/listener changes expect `view-v1` while the test expects `view-content-v1`.

- [x] **Step 2: Review git diff**
  - Run `git status --short` and `git diff --stat`.
  - Do not include unrelated user changes unless they are part of this work.

- [ ] **Step 3: Commit**
  - Commit the completed implementation and verification updates.
