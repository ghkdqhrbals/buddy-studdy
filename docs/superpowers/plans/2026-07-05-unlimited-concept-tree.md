# Unlimited Concept Tree Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store study question coverage as an unlimited-depth concept tree and prompt questions with the selected leaf's full concept path.

**Architecture:** Extend the existing concept table with parent/depth/path metadata and keep `study_question_coverage` as the leaf-angle coverage table. Parse OpenAI coverage blueprints recursively, persist all concepts, create coverage only for leaves, and return full path metadata during selection.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, Flyway SQL migrations, JUnit/AssertJ.

---

### Task 1: Schema And Entity Tree Metadata

**Files:**
- Modify: `backend/domain/src/main/kotlin/com/buddystudy/study/domain/entity/StudyQuestionConceptEntity.kt`
- Create: `backend/tutor/src/main/resources/db/migration/V34__concept_tree_metadata.sql`

- [ ] **Step 1: Write the failing persistence/entity test**

Add a test to `backend/infra/src/test/kotlin/com/buddystudy/backend/study/adapter/outbound/persistence/StudyQuestionCoveragePersistenceAdapterTest.kt` that creates a nested blueprint and asserts parent/depth/path/leaf fields are stored.

- [ ] **Step 2: Run the focused test**

Run: `cd backend && ./gradlew :infra:test --tests '*StudyQuestionCoveragePersistenceAdapterTest'`

Expected before implementation: compilation fails because tree fields do not exist.

- [ ] **Step 3: Add entity fields**

Add nullable `parentConceptId`, integer `depth`, string `path`, string `conceptPath`, and boolean `leaf` to `StudyQuestionConceptEntity`.

- [ ] **Step 4: Add Flyway migration**

Add nullable/backfilled columns, backfill current flat concepts, then make `depth`, `path`, `concept_path`, and `leaf` non-null. Add indexes on `(study_id, parent_concept_id, display_order, id)` and `(study_id, path)`.

### Task 2: Recursive Blueprint Model And Parser

**Files:**
- Modify: `backend/application/src/main/kotlin/com/buddystudy/backend/study/application/port/outbound/OpenAIPort.kt`
- Modify: `backend/infra/src/main/kotlin/com/buddystudy/backend/study/adapter/outbound/openai/OpenAIRequestExecutor.kt`
- Test: `backend/infra/src/test/kotlin/com/buddystudy/backend/study/adapter/outbound/openai/OpenAIRequestExecutorTest.kt`

- [ ] **Step 1: Write parser tests**

Test that a blueprint with nested `children` parses into recursive `QuestionCoverageConcept.children`, and that leaf angles are preserved at arbitrary nesting.

- [ ] **Step 2: Run parser tests and verify red**

Run: `cd backend && ./gradlew :infra:test --tests '*OpenAIRequestExecutorTest'`

Expected before implementation: compilation fails because `children` does not exist.

- [ ] **Step 3: Add recursive DTO field**

Add `children: List<QuestionCoverageConcept> = emptyList()` to `OpenAIPort.QuestionCoverageConcept`.

- [ ] **Step 4: Update prompt and parser**

Change the coverage blueprint prompt to request recursive `children`. Implement a recursive parser with no maximum depth.

### Task 3: Persist Tree And Select Leaf Path

**Files:**
- Modify: `backend/application/src/main/kotlin/com/buddystudy/backend/study/application/port/outbound/StudyPersistencePorts.kt`
- Modify: `backend/infra/src/main/kotlin/com/buddystudy/backend/study/adapter/outbound/persistence/StudyQuestionCoverageRepository.kt`
- Test: `backend/infra/src/test/kotlin/com/buddystudy/backend/study/adapter/outbound/persistence/StudyQuestionCoveragePersistenceAdapterTest.kt`

- [ ] **Step 1: Write selection tests**

Assert nested concepts are all stored, only leaves receive coverage rows, and `selectNext()` returns `conceptPath` for the leaf.

- [ ] **Step 2: Run selection tests and verify red**

Run: `cd backend && ./gradlew :infra:test --tests '*StudyQuestionCoveragePersistenceAdapterTest'`

Expected before implementation: tests fail because the adapter stores only flat concepts.

- [ ] **Step 3: Extend selection model**

Add `conceptPath` and `conceptKeyPath` to `QuestionCoverageSelection`.

- [ ] **Step 4: Implement recursive persistence**

Persist each concept with `parentConceptId`, `depth`, stable key path, display path, and `leaf`. Create coverage rows only for leaves. Keep existing flat blueprint behavior.

### Task 4: Prompt With Full Concept Path

**Files:**
- Modify: `backend/application/src/main/kotlin/com/buddystudy/backend/study/application/prompt/QuestionPromptProvider.kt`
- Modify: `backend/application/src/main/kotlin/com/buddystudy/backend/study/application/service/StudyService.kt`
- Modify: `backend/application/src/main/kotlin/com/buddystudy/backend/study/application/service/ScheduledQuestionService.kt`
- Test: `backend/application/src/test/kotlin/com/buddystudy/backend/study/QuestionPromptProviderTest.kt`
- Test: `backend/application/src/test/kotlin/com/buddystudy/backend/study/StudyServiceTest.kt`
- Test: `backend/infra/src/test/kotlin/com/buddystudy/backend/study/adapter/inbound/scheduler/QuestionSchedulerTest.kt`

- [ ] **Step 1: Write prompt/service tests**

Assert generated question prompts include `Focus concept path: Root > Child > Leaf`, `Focus concept: Leaf`, and `Question angle: ...`.

- [ ] **Step 2: Run focused tests and verify red**

Run: `cd backend && ./gradlew :application:test --tests '*QuestionPromptProviderTest' --tests '*StudyServiceTest'`

Expected before implementation: tests fail because `QuestionCoverageGuide` has no concept path.

- [ ] **Step 3: Extend `QuestionCoverageGuide`**

Add `conceptPath` and pass it from both manual and scheduled question generation.

- [ ] **Step 4: Update tests and fakes**

Update fake `QuestionCoverageSelection` construction in application and infra tests with path fields.

### Task 5: Verify And Commit

**Files:**
- All modified backend files.

- [ ] **Step 1: Run focused backend tests**

Run:

```sh
cd backend
./gradlew :application:test --tests '*QuestionPromptProviderTest' --tests '*StudyServiceTest'
./gradlew :infra:test --tests '*StudyQuestionCoveragePersistenceAdapterTest' --tests '*OpenAIRequestExecutorTest' --tests '*QuestionSchedulerTest'
```

- [ ] **Step 2: Run broader backend tests if focused tests pass**

Run: `cd backend && ./gradlew test`

- [ ] **Step 3: Commit implementation**

Run:

```sh
git add backend docs/superpowers/plans/2026-07-05-unlimited-concept-tree.md
git commit -m "Add unlimited concept tree coverage"
```
