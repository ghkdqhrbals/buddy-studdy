package com.buddystudy.backend.study

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.port.inbound.CreateRootStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicCommand
import com.buddystudy.backend.study.application.port.inbound.CreateStudyTopicsCommand
import com.buddystudy.backend.study.application.port.inbound.ExpectedStudyMetadata
import com.buddystudy.backend.study.application.port.inbound.UpdateStudyCommand
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.StudySyncService
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.Optional

class StudySyncServiceTest {
    private val studies = FakeStudyPort()
    private val questions = FakeQuestionPort()
    private val questionStats = FakeQuestionStatsPort()
    private val service = StudySyncService(studies, questions, questionStats, EmptyContentLocalizationPort())
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `study list does not query pending question once per study`(): Unit = runBlocking {
        studies.rows += study(id = 11, topic = "Swift")
        studies.rows += study(id = 12, topic = "Kotlin")
        questions.pendingRows += pendingQuestion(id = 101, studyId = 11, topic = "Swift").apply {
            answer = "A process owns resources while a thread is an execution flow."
            answeredAt = Instant.parse("2026-06-10T00:05:00Z")
            gradingRequestId = "grading-101"
            gradingStatus = AnswerGradingStatus.JUDGING
        }
        questions.pendingRows += pendingQuestion(id = 102, studyId = 12, topic = "Kotlin")
        questionStats.rows += QuestionStatsEntity(questionId = 101, viewCount = 3)
        questionStats.rows += QuestionStatsEntity(questionId = 102, viewCount = 4)

        val response = service.study(principal, limit = 20, offset = 0, query = null)

        assertThat(response.studies.map { it.pendingQuestion?.id }).containsExactly("101", "102")
        assertThat(response.studies.map { it.pendingQuestion?.viewCount }).containsExactly(3, 4)
        assertThat(response.studies.first().pendingQuestion?.answer)
            .isEqualTo("A process owns resources while a thread is an execution flow.")
        assertThat(response.studies.first().pendingQuestion?.gradingRequestId).isEqualTo("grading-101")
        assertThat(response.studies.first().pendingQuestion?.gradingStatus?.name).isEqualTo("JUDGING")
        assertThat(questions.findPendingByStudyIdCalls).isZero()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
        assertThat(studies.findByParentCalls).isZero()
    }

    @Test
    fun `child study page includes only owned direct children in exact sibling order`(): Unit = runBlocking {
        studies.rows += study(10, "Databases")
        studies.rows += study(14, "Fourth").apply { parentStudyId = 10; sortOrder = 2 }
        studies.rows += study(13, "Third").apply { parentStudyId = 10; sortOrder = 1 }
        studies.rows += study(11, "First").apply { parentStudyId = 10; sortOrder = 0 }
        studies.rows += study(12, "Second").apply { parentStudyId = 10; sortOrder = 1 }
        studies.rows += study(21, "Other parent").apply { parentStudyId = 20 }
        studies.rows += study(22, "Grandchild").apply { parentStudyId = 11 }
        studies.rows += study(23, "Other owner").apply { parentStudyId = 10; userId = 99 }
        questions.pendingRows += pendingQuestion(101, 11, "First")
        questions.pendingRows += pendingQuestion(102, 12, "Second")
        questions.pendingRows += pendingQuestion(103, 13, "Third")
        questionStats.rows += QuestionStatsEntity(questionId = 102, viewCount = 4)

        val page = service.study(principal, 2, 1, null, "ko", parentStudyId = 10)

        assertThat(page.studies.map { it.id }).containsExactly(12L, 13L)
        assertThat(page.studies.map { it.parentStudyId }).containsOnly(10L)
        assertThat(page.totalCount).isEqualTo(4)
        assertThat(page.limit).isEqualTo(2)
        assertThat(page.offset).isEqualTo(1)
        assertThat(page.studies.map { it.pendingQuestion?.id }).containsExactly("102", "103")
        assertThat(page.studies.first().pendingQuestion?.viewCount).isEqualTo(4)
        assertThat(studies.lastChildPageable?.offset).isEqualTo(1L)
        assertThat(studies.lastChildPageable?.pageSize).isEqualTo(2)
        assertThat(studies.findByParentCalls).isEqualTo(1)
        assertThat(studies.findByUserCalls).isZero()
        assertThat(studies.findByUserAndQueryCalls).isZero()
        assertThat(studies.saveCalls).isZero()
        assertThat(questions.findPendingByStudyIdCalls).isZero()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isEqualTo(1)
    }

    @Test
    fun `child study search trims search and keeps the parent filter and filtered total`(): Unit = runBlocking {
        studies.rows += study(10, "Databases")
        studies.rows += study(11, "Redis cache").apply { parentStudyId = 10 }
        studies.rows += study(12, "Key value").apply { parentStudyId = 10; customPrompt = "Use Redis examples" }
        studies.rows += study(13, "SQL").apply { parentStudyId = 10 }
        studies.rows += study(14, "Redis other parent").apply { parentStudyId = 20 }

        val page = service.study(principal, 10, 0, "  redis  ", "en", parentStudyId = 10)

        assertThat(page.studies.map { it.id }).containsExactly(11L, 12L)
        assertThat(page.totalCount).isEqualTo(2)
        assertThat(studies.lastChildQuery).isEqualTo("redis")
        assertThat(studies.findByUserAndQueryCalls).isZero()
        assertThat(studies.saveCalls).isZero()
    }

    @Test
    fun `owned empty parent returns an empty page without loading question records`(): Unit = runBlocking {
        studies.rows += study(10, "Empty")

        val page = service.study(principal, 10, 0, "  ", "ko", parentStudyId = 10)

        assertThat(page.studies).isEmpty()
        assertThat(page.totalCount).isZero()
        assertThat(studies.lastChildQuery).isNull()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isZero()
    }

    @Test
    fun `missing and foreign child list parents both return not found before page access`(): Unit = runBlocking {
        studies.rows += study(99, "Foreign parent").apply { userId = 99 }
        listOf(98L, 99L).forEach { parentId ->
            val failure = runCatching {
                service.study(principal, 10, 0, null, "ko", parentId)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).status).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(failure.code).isEqualTo(ApiErrorCode.STUDY_SETTINGS_MISSING)
        }
        assertThat(studies.findByParentCalls).isZero()
        assertThat(studies.findByUserCalls).isZero()
        assertThat(studies.saveCalls).isZero()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
    }

    @Test
    fun `child use case rejects unbounded or invalid pages directly`(): Unit = runBlocking {
        listOf(Triple(10L, 0, 0), Triple(10L, 501, 0), Triple(10L, 10, -1), Triple(0L, 10, 0))
            .forEach { (parentId, limit, offset) ->
                val failure = runCatching {
                    service.study(principal, limit, offset, null, "ko", parentId)
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(ApiException::class.java)
                assertThat((failure as ApiException).code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
            }
        assertThat(studies.findByParentCalls).isZero()
    }

    @Test
    fun `study detail returns pending and latest completed questions without loading the study list`(): Unit = runBlocking {
        studies.rows += study(id = 11, topic = "Swift")
        questions.pendingRows += pendingQuestion(id = 103, studyId = 11, topic = "Swift").apply {
            answer = "Submitted answer"
            gradingRequestId = "grading-103"
            gradingStatus = AnswerGradingStatus.JUDGING
            status = QuestionStatus.GRADING
        }
        questions.completedRows += pendingQuestion(id = 102, studyId = 11, topic = "Swift").apply {
            answer = "Completed answer"
            score = 92
            correct = true
            feedback = "Good"
            explanation = "Detailed feedback"
            status = QuestionStatus.GRADED
            answeredAt = Instant.parse("2026-06-10T00:10:00Z")
        }
        questionStats.rows += QuestionStatsEntity(questionId = 102, viewCount = 7)
        questionStats.rows += QuestionStatsEntity(questionId = 103, viewCount = 3)

        val response = service.study(principal, studyId = 11, language = "ko")

        assertThat(response.id).isEqualTo(11)
        assertThat(response.pendingQuestion?.id).isEqualTo("103")
        assertThat(response.pendingQuestion?.questionStatus).isEqualTo(QuestionStatus.GRADING)
        assertThat(response.latestQuestion?.id).isEqualTo("102")
        assertThat(response.latestQuestion?.answer).isEqualTo("Completed answer")
        assertThat(response.latestQuestion?.gradingResult?.feedback).isEqualTo("Good")
        assertThat(response.latestQuestion?.viewCount).isEqualTo(7)
        assertThat(questions.findLatestPendingByStudyIdsCalls).isEqualTo(1)
        assertThat(questions.findLatestCompletedCalls).isEqualTo(1)
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
    }

    @Test
    fun `study detail rejects a study owned by another user`() {
        studies.rows += study(id = 11, topic = "Swift").apply { userId = 99 }

        assertThatThrownBy {
            runBlocking { service.study(principal, studyId = 11, language = "ko") }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `create new study does not query pending question`(): Unit = runBlocking {
        val response = service.createStudy(
            principal,
            com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand(topic = "Postgres"),
        )

        assertThat(response.topic).isEqualTo("Postgres")
        assertThat(response.pendingQuestion).isNull()
        assertThat(response.nextDueAt).isNotNull()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isZero()
    }

    @Test
    fun `create-only root uses enabled scheduling defaults without creating a question`(): Unit = runBlocking {
        val response = service.createRootStudy(
            principal,
            CreateRootStudyCommand(topic = "  Operating Systems  ", difficultyLevel = 7),
        )

        assertThat(response.created).isTrue()
        assertThat(response.id).isPositive()
        assertThat(response.parentStudyId).isNull()
        assertThat(response.topic).isEqualTo("Operating Systems")
        assertThat(response.difficultyLevel).isEqualTo(7)
        assertThat(response.enabled).isTrue()
        assertThat(response.activeForQuestions).isTrue()
        val created = studies.rows.single()
        assertThat(created.parentStudyId).isNull()
        assertThat(created.intervalMinutes).isEqualTo(15)
        assertThat(created.enabled).isTrue()
        assertThat(created.activeForQuestions).isTrue()
        assertThat(created.nextDueAt).isNotNull()
        assertThat(studies.events.first()).isEqualTo("lock:7")
        assertThat(questions.findPendingByStudyIdCalls).isZero()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(questions.findLatestCompletedCalls).isZero()
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isZero()
    }

    @Test
    fun `create-only normalized existing root is returned without changing any setting`(): Unit = runBlocking {
        val existing = study(id = 11, topic = "Redis Streams").apply {
            difficultyLevel = 3
            intervalMinutes = 47
            enabled = false
            activeForQuestions = false
            notificationSound = "bell.caf"
            customPrompt = "Keep this instruction"
            openaiModel = "fixture-model"
            maxHistoryCount = 231
            nextDueAt = Instant.parse("2026-09-01T10:01:00Z")
            scheduleClaimedUntil = nextDueAt!!.plusSeconds(35)
            lastSentAt = nextDueAt!!.minusSeconds(600)
            lastError = "Existing scheduling state"
        }
        studies.rows += existing
        val before = completeStudyState(existing)

        val response = service.createRootStudy(
            principal,
            CreateRootStudyCommand(topic = "  redis \n streams  ", difficultyLevel = 9),
        )

        assertThat(response.created).isFalse()
        assertThat(response.id).isEqualTo(11)
        assertThat(response.parentStudyId).isNull()
        assertThat(response.topic).isEqualTo("Redis Streams")
        assertThat(response.difficultyLevel).isEqualTo(3)
        assertThat(response.enabled).isFalse()
        assertThat(response.activeForQuestions).isFalse()
        assertThat(completeStudyState(existing)).isEqualTo(before)
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.events).containsExactly("lock:7", "owner-list")
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isZero()
    }

    @Test
    fun `create-only root rejects a normalized name owned only by a child without mutation`(): Unit = runBlocking {
        val child = study(id = 12, topic = "Redis Streams").apply { parentStudyId = 11 }
        studies.rows += study(id = 11, topic = "Redis")
        studies.rows += child
        val before = completeStudyState(child)

        val failure = runCatching {
            service.createRootStudy(principal, CreateRootStudyCommand(" redis   streams "))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(failure.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(completeStudyState(child)).isEqualTo(before)
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.events).containsExactly("lock:7", "owner-list")
    }

    @Test
    fun `create-only root validates bounded metadata before taking the owner lock`(): Unit = runBlocking {
        for (command in listOf(
            CreateRootStudyCommand(""),
            CreateRootStudyCommand("x".repeat(256)),
            CreateRootStudyCommand("Redis", difficultyLevel = 0),
            CreateRootStudyCommand("Redis", difficultyLevel = 11),
        )) {
            val failure = runCatching { service.createRootStudy(principal, command) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ApiException::class.java)
            assertThat((failure as ApiException).status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            assertThat(failure.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        assertThat(studies.events).isEmpty()
        assertThat(studies.saveCalls).isZero()
    }

    @Test
    fun `saving existing study with same schedule keeps existing job due time`(): Unit = runBlocking {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val existingDueAt = now.plusSeconds(600)
        val existingStudy = study(id = 11, topic = "Postgres").apply {
            intervalMinutes = 15
            enabled = true
            nextDueAt = existingDueAt
        }
        studies.rows += existingStudy

        val response = service.createStudy(
            principal,
            com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand(
                topic = "Postgres",
                intervalMinutes = 15,
                enabled = true,
                customPrompt = "Updated prompt only",
            ),
        )

        assertThat(response.nextDueAt).isEqualTo(existingDueAt)
        assertThat(existingStudy.nextDueAt).isEqualTo(existingDueAt)
    }

    @Test
    fun `saving existing study with changed interval reschedules job`(): Unit = runBlocking {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val existingDueAt = now.plusSeconds(600)
        val existingStudy = study(id = 11, topic = "Postgres").apply {
            intervalMinutes = 15
            enabled = true
            nextDueAt = existingDueAt
        }
        studies.rows += existingStudy

        service.createStudy(
            principal,
            com.buddystudy.backend.study.application.port.inbound.CreateStudyCommand(
                topic = "Postgres",
                intervalMinutes = 30,
                enabled = true,
            ),
        )

        assertThat(existingStudy.nextDueAt).isAfter(existingDueAt)
    }

    @Test
    fun `child studies keep their parent and sibling order`(): Unit = runBlocking {
        studies.rows += study(id = 11, topic = "Redis")

        val response = service.createStudyTopic(
            principal,
            parentStudyId = 11,
            CreateStudyTopicCommand(
                topic = "Streams",
                sortOrder = 3,
                difficultyLevel = 7,
            ),
        )

        assertThat(response.parentStudyId).isEqualTo(11)
        assertThat(response.sortOrder).isEqualTo(3)
        assertThat(response.difficultyLevel).isEqualTo(7)
        assertThat(response.enabled).isFalse()
        assertThat(response.nextDueAt).isNull()
        assertThat(studies.rows.last().parentStudyId).isEqualTo(11)
    }

    @Test
    fun `retrying the same child topic under the same parent is idempotent`(): Unit = runBlocking {
        studies.rows += study(id = 11, topic = "Redis")

        val first = service.createStudyTopicWithOutcome(
            principal,
            parentStudyId = 11,
            CreateStudyTopicCommand(topic = "Redis Streams", sortOrder = 1, difficultyLevel = 6),
        )
        val retried = service.createStudyTopicWithOutcome(
            principal,
            parentStudyId = 11,
            CreateStudyTopicCommand(topic = "  redis   streams ", sortOrder = 1, difficultyLevel = 6),
        )

        assertThat(retried.id).isEqualTo(first.id)
        assertThat(first.created).isTrue()
        assertThat(retried.created).isFalse()
        assertThat(retried.parentStudyId).isEqualTo(11)
        assertThat(retried.topic).isEqualTo("Redis Streams")
        assertThat(studies.rows.count { it.parentStudyId == 11L }).isEqualTo(1)
    }

    @Test
    fun `selected child can be saved at depth four but no fifth level is created`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        studies.rows += study(12, "One").apply { parentStudyId = 11 }
        studies.rows += study(13, "Two").apply { parentStudyId = 12 }
        studies.rows += study(14, "Three").apply { parentStudyId = 13 }

        val fourth = service.createStudyTopicWithOutcome(principal, 14, CreateStudyTopicCommand("Four", difficultyLevel = 8))
        val savedCount = studies.saveCalls
        val failure = runCatching {
            service.createStudyTopicWithOutcome(principal, fourth.id, CreateStudyTopicCommand("Five"))
        }.exceptionOrNull()

        assertThat(fourth.created).isTrue()
        assertThat(fourth.parentStudyId).isEqualTo(14)
        assertThat(fourth.difficultyLevel).isEqualTo(8)
        assertThat(fourth.curriculumTerminal).isTrue()
        assertThat(studies.rows.last().curriculumTerminal).isTrue()
        assertThat(failure).isInstanceOf(ApiException::class.java)
        assertThat((failure as ApiException).status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(studies.rows).hasSize(5)
        assertThat(studies.saveCalls).isEqualTo(savedCount)
        assertThat(questions.pendingRows).isEmpty()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
    }

    @Test
    fun `selected topic batch appends only direct children and replay preserves existing settings`(): Unit = runBlocking {
        val root = study(11, "Root").apply { customPrompt = "Keep root settings"; intervalMinutes = 90 }
        val existing = study(12, "Transactions").apply {
            parentStudyId = 11; sortOrder = 4; difficultyLevel = 2; activeForQuestions = false
        }
        studies.rows += listOf(root, existing)
        val beforeRoot = completeStudyState(root)
        val beforeExisting = completeStudyState(existing)
        val command = CreateStudyTopicsCommand(listOf(" transactions ", " Indexes ", "Replication"), difficultyLevel = 8)

        val result = service.createStudyTopics(principal, 11, command)
        val replay = service.createStudyTopics(principal, 11, command)

        assertThat(result.parentStudyId).isEqualTo(11)
        assertThat(result.maxDepth).isEqualTo(4)
        assertThat(result.topics.map { it.created }).containsExactly(false, true, true)
        assertThat(result.topics.map { it.topic }).containsExactly("Transactions", "Indexes", "Replication")
        assertThat(result.topics.map { it.parentStudyId }).containsOnly(11L)
        assertThat(result.topics.drop(1).map { it.difficultyLevel }).containsOnly(8)
        assertThat(result.topics.drop(1).map { it.enabled }).containsOnly(false)
        assertThat(replay.topics.map { it.created }).containsOnly(false)
        assertThat(replay.topics.map { it.id }).containsExactlyElementsOf(result.topics.map { it.id })
        assertThat(studies.rows).hasSize(4)
        assertThat(studies.saveCalls).isEqualTo(2)
        assertThat(completeStudyState(root)).isEqualTo(beforeRoot)
        assertThat(completeStudyState(existing)).isEqualTo(beforeExisting)
        assertThat(studies.rows.takeLast(2).map { it.sortOrder }).isSorted().allMatch { it > 4 }
        assertThat(questions.pendingRows).isEmpty()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
    }

    @Test
    fun `a later conflicting topic rejects the whole selected batch before any save`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        studies.rows += study(12, "Other root")
        studies.rows += study(13, "Transactions").apply { parentStudyId = 12 }

        val error = runCatching {
            service.createStudyTopics(principal, 11, CreateStudyTopicsCommand(listOf("New first selection", "transactions")))
        }.exceptionOrNull() as ApiException

        assertThat(error.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.rows).hasSize(3)
    }

    @Test
    fun `batch validates size normalized duplicates topic lengths and difficulty before saving`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        listOf(
            CreateStudyTopicsCommand(emptyList()),
            CreateStudyTopicsCommand((1..11).map { "Topic $it" }),
            CreateStudyTopicsCommand(listOf("Transactions", " transactions ")),
            CreateStudyTopicsCommand(listOf("Valid", "   ")),
            CreateStudyTopicsCommand(listOf("x".repeat(256))),
            CreateStudyTopicsCommand(listOf("Valid"), difficultyLevel = 11),
        ).forEach { command ->
            val error = runCatching { service.createStudyTopics(principal, 11, command) }.exceptionOrNull() as ApiException
            assertThat(error.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        }
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.rows).hasSize(1)
    }

    @Test
    fun `batch checks owned parent and immutable parent metadata under the creation lock`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        studies.rows += study(12, "Foreign").apply { userId = 99 }
        val selection = CreateStudyTopicsCommand(listOf("Transactions"))
        val foreign = runCatching { service.createStudyTopics(principal, 12, selection) }.exceptionOrNull() as ApiException
        val changed = runCatching {
            service.createStudyTopics(principal, 11, selection.copy(expectedParent = ExpectedStudyMetadata(null, "Previous root", 5)))
        }.exceptionOrNull() as ApiException

        assertThat(foreign.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(changed.errorCode).isEqualTo(ApiErrorCode.STUDY_TREE_CHANGED)
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.events).contains("lock:7")
    }

    @Test
    fun `batch allows depth four and retains legacy deeper topics without adding a fifth level`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        (12L..16L).forEach { id -> studies.rows += study(id, "Level ${id - 11}").apply { parentStudyId = id - 1 } }
        val fourth = service.createStudyTopics(principal, 14, CreateStudyTopicsCommand(
            listOf("Selected fourth"), curriculumTerminalByTopic = mapOf("Selected fourth" to false),
        ))
        assertThat(fourth.topics.single().created).isTrue()
        assertThat(fourth.topics.single().curriculumTerminal).isTrue()
        val saved = studies.saveCalls

        val legacy = service.createStudyTopics(principal, 15, CreateStudyTopicsCommand(listOf("Level 5")))
        val failed = runCatching {
            service.createStudyTopics(principal, 15, CreateStudyTopicsCommand(listOf("Level 5", "New fifth")))
        }.exceptionOrNull() as ApiException

        assertThat(legacy.topics.single().created).isFalse()
        assertThat(failed.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(studies.saveCalls).isEqualTo(saved)
        assertThat(studies.rows).hasSize(7)
        assertThat(studies.deleteCalls).isZero()
    }

    @Test
    fun `legacy fifth level remains readable editable and idempotently returned without extending it`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        (12L..16L).forEach { id -> studies.rows += study(id, "Level ${id - 11}").apply { parentStudyId = id - 1 } }
        val legacy = studies.rows.last()
        val before = completeStudyState(legacy)

        val retry = service.createStudyTopicWithOutcome(principal, 15, CreateStudyTopicCommand("Level 5", difficultyLevel = 9))
        assertThat(retry.created).isFalse()
        assertThat(retry.id).isEqualTo(legacy.id)
        assertThat(completeStudyState(legacy)).isEqualTo(before)

        val edited = service.updateStudy(principal, legacy.id, UpdateStudyCommand(difficultyLevel = 7))
        assertThat(edited.difficultyLevel).isEqualTo(7)
        assertThat(edited.parentStudyId).isEqualTo(15)
        assertThat(studies.rows).hasSize(6)
        assertThat(studies.deleteCalls).isZero()
        assertThat(runCatching {
            service.createStudyTopic(principal, legacy.id, CreateStudyTopicCommand("New sixth level"))
        }.exceptionOrNull()).isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `invalid cyclic parent chain cannot bypass new descendant depth validation`(): Unit = runBlocking {
        studies.rows += study(11, "One").apply { parentStudyId = 12 }
        studies.rows += study(12, "Two").apply { parentStudyId = 11 }

        assertThat(runCatching {
            service.createStudyTopic(principal, 11, CreateStudyTopicCommand("Child"))
        }.exceptionOrNull()).isInstanceOf(ApiException::class.java)
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.rows).hasSize(2)
    }

    @Test
    fun `same normalized topic is rejected across the study tree`() {
        studies.rows += study(id = 11, topic = "Redis")
        studies.rows += study(id = 12, topic = "Kafka")

        runBlocking {
            service.createStudyTopic(
                principal,
                parentStudyId = 11,
                CreateStudyTopicCommand(topic = "Redis Streams"),
            )
        }

        assertThatThrownBy {
            runBlocking {
                service.createStudyTopic(
                    principal,
                    parentStudyId = 12,
                    CreateStudyTopicCommand(topic = "  redis   streams "),
                )
            }
        }.isInstanceOf(ApiException::class.java)
        assertThat(studies.rows.count { it.topic == "Redis Streams" }).isEqualTo(1)
    }

    @Test
    fun `child study rejects a parent owned by another user`() {
        studies.rows += study(id = 11, topic = "Redis").apply { userId = 99 }

        assertThatThrownBy {
            runBlocking {
                service.createStudyTopic(
                    principal,
                    parentStudyId = 11,
                    CreateStudyTopicCommand(topic = "Streams"),
                )
            }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `deleting study preserves records and deletes only the owned study`(): Unit = runBlocking {
        studies.rows += study(id = 8, topic = "Redis")
        questions.completedRows += pendingQuestion(id = 81, studyId = 8, topic = "Redis")

        service.deleteStudy(principal, studyId = 8)

        assertThat(studies.rows).noneMatch { it.id == 8L }
        assertThat(questions.completedRows).hasSize(1)
        assertThat(questions.softDeletedQuestionIds).isEmpty()
    }

    @Test
    fun `topic and level patches preserve every unrelated setting and never touch question state`(): Unit = runBlocking {
        val row = study(11, "Redis").apply {
            parentStudyId = 10
            sortOrder = 8
            difficultyLevel = 3
            intervalMinutes = 47
            enabled = false
            activeForQuestions = false
            notificationSound = "bell.caf"
            customPrompt = "Keep the existing instruction"
            openaiModel = "fixture-model"
            maxHistoryCount = 231
            nextDueAt = Instant.parse("2026-09-01T10:01:00Z")
            scheduleClaimedUntil = nextDueAt!!.plusSeconds(35)
            lastSentAt = nextDueAt!!.minusSeconds(600)
            lastError = "Existing scheduling state"
        }
        studies.rows += row
        val original = unrelatedMetadata(row)
        questions.pendingRows += pendingQuestion(111, 11, "Redis").apply { answer = "Unsubmitted draft" }
        questions.completedRows += pendingQuestion(112, 11, "Redis").apply { answer = "Original answer"; score = 85 }

        val renamed = service.updateStudy(principal, 11, UpdateStudyCommand(topic = "  Redis Streams  "))
        assertThat(renamed.topic).isEqualTo("Redis Streams")
        assertThat(renamed.difficultyLevel).isEqualTo(3)
        val levelChanged = service.updateStudy(principal, 11, UpdateStudyCommand(difficultyLevel = 7))

        assertThat(levelChanged.id).isEqualTo(11)
        assertThat(levelChanged.topic).isEqualTo("Redis Streams")
        assertThat(levelChanged.difficultyLevel).isEqualTo(7)
        assertThat(unrelatedMetadata(row)).isEqualTo(original)
        assertThat(studies.saveCalls).isZero()
        assertThat(studies.metadataUpdateCalls).isEqualTo(2)
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(questions.findLatestCompletedCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isZero()
        assertThat(questions.pendingRows.single().answer).isEqualTo("Unsubmitted draft")
        assertThat(questions.completedRows.single().topic).isEqualTo("Redis")
        assertThat(questions.completedRows.single().score).isEqualTo(85)
        assertThat(questions.softDeletedQuestionIds).isEmpty()
    }

    @Test
    fun `metadata patch rejects invalid boundaries and reuses normalized owned topic uniqueness`(): Unit = runBlocking {
        studies.rows += study(11, "Kotlin")
        studies.rows += study(12, "Redis Streams")
        studies.rows += study(99, "Foreign name").apply { userId = 99 }
        val invalid = listOf(
            0L to UpdateStudyCommand(topic = "Name"), 11L to UpdateStudyCommand(),
            11L to UpdateStudyCommand(topic = "  \t"), 11L to UpdateStudyCommand(topic = "x".repeat(256)),
            11L to UpdateStudyCommand(difficultyLevel = 0), 11L to UpdateStudyCommand(difficultyLevel = 11),
        )
        invalid.forEach { (id, command) ->
            val error = runCatching { service.updateStudy(principal, id, command) }.exceptionOrNull() as ApiException
            assertThat(error.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        assertThat(studies.events).isEmpty()
        val duplicate = runCatching {
            service.updateStudy(principal, 11, UpdateStudyCommand(topic = "  REDIS \n STREAMS "))
        }.exceptionOrNull() as ApiException
        assertThat(duplicate.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(studies.metadataUpdateCalls).isZero()
        assertThat(service.updateStudy(principal, 11, UpdateStudyCommand(topic = "kotlin")).topic).isEqualTo("kotlin")
        assertThat(service.updateStudy(principal, 11, UpdateStudyCommand(topic = "Foreign name")).id).isEqualTo(11)
        val foreign = runCatching { service.updateStudy(principal, 99, UpdateStudyCommand(difficultyLevel = 4)) }
            .exceptionOrNull() as ApiException
        assertThat(foreign.code).isEqualTo(ApiErrorCode.STUDY_SETTINGS_MISSING)
        assertThat(studies.rows.single { it.id == 99L }.difficultyLevel).isEqualTo(5)
    }

    @Test
    fun `metadata patch returns the current partial update and rejects a disappeared node`(): Unit = runBlocking {
        val row = study(11, "Redis")
        studies.rows += row
        studies.beforeMetadataUpdate = { row.customPrompt = "Concurrent preference"; row.intervalMinutes = 91 }

        val result = service.updateStudy(principal, 11, UpdateStudyCommand(difficultyLevel = 4))

        assertThat(result.customPrompt).isEqualTo("Concurrent preference")
        assertThat(result.intervalMinutes).isEqualTo(91)
        assertThat(result.difficultyLevel).isEqualTo(4)
        assertThat(studies.saveCalls).isZero()
        studies.beforeMetadataUpdate = { studies.rows.remove(row) }
        val disappeared = runCatching { service.updateStudy(principal, 11, UpdateStudyCommand(topic = "New name")) }
            .exceptionOrNull() as ApiException
        assertThat(disappeared.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(studies.rows).isEmpty()
    }

    @Test
    fun `voice metadata patch compares the frozen identity inside the owner fence`(): Unit = runBlocking {
        val row = study(11, "Redis").apply {
            parentStudyId = 10
            difficultyLevel = 3
        }
        studies.rows += row
        val expected = ExpectedStudyMetadata(parentStudyId = 10, topic = "Redis", difficultyLevel = 3)

        val concurrentChanges = listOf<() -> Unit>(
            { row.topic = "Changed elsewhere" },
            { row.parentStudyId = 12 },
            { row.difficultyLevel = 8 },
        )
        concurrentChanges.forEach { change ->
            row.topic = "Redis"
            row.parentStudyId = 10
            row.difficultyLevel = 3
            change()

            val error = runCatching {
                service.updateStudy(
                    principal,
                    11,
                    UpdateStudyCommand(topic = "Redis Streams", expectedCurrent = expected),
                )
            }.exceptionOrNull() as ApiException

            assertThat(error.status).isEqualTo(HttpStatus.CONFLICT)
            assertThat(error.code).isEqualTo(ApiErrorCode.STUDY_TREE_CHANGED)
        }
        assertThat(studies.metadataUpdateCalls).isZero()

        row.topic = "Redis"
        row.parentStudyId = 10
        row.difficultyLevel = 3
        val saved = service.updateStudy(
            principal,
            11,
            UpdateStudyCommand(topic = "Redis Streams", expectedCurrent = expected),
        )
        assertThat(saved.topic).isEqualTo("Redis Streams")
        assertThat(studies.metadataUpdateCalls).isEqualTo(1)
    }

    @Test
    fun `create child update and delete acquire the same owner fence before any study read`(): Unit = runBlocking {
        val root = service.createStudy(principal, CreateStudyCommand(topic = "Root"))
        assertThat(studies.events.first()).isEqualTo("lock:7")
        studies.events.clear()
        val child = service.createStudyTopic(principal, root.id, CreateStudyTopicCommand(topic = "Child"))
        assertThat(studies.events.first()).isEqualTo("lock:7")
        studies.events.clear()
        service.updateStudy(principal, child.id, UpdateStudyCommand(topic = "Renamed"))
        assertThat(studies.events.first()).isEqualTo("lock:7")
        studies.events.clear()
        service.deleteStudy(principal, child.id)
        assertThat(studies.events.first()).isEqualTo("lock:7")
        studies.events.clear()
        studies.ownerAvailable = false
        val failure = runCatching { service.updateStudy(principal, root.id, UpdateStudyCommand(difficultyLevel = 6)) }
            .exceptionOrNull() as ApiException
        assertThat(failure.code).isEqualTo(ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThat(studies.events).containsExactly("lock:7")
    }

    @Test
    fun `guarded deletion compares the complete subtree as a set and preserves existing records`(): Unit = runBlocking {
        studies.rows += study(10, "Root")
        studies.rows += study(11, "Child").apply { parentStudyId = 10 }
        studies.rows += study(12, "Grandchild").apply { parentStudyId = 11 }
        studies.rows += study(13, "Unrelated")
        questions.completedRows += pendingQuestion(101, 11, "Child").apply { answer = "Prior answer"; score = 85 }

        service.deleteStudy(principal, 10, expectedStudyIds = listOf(12L, 10L, 11L))

        assertThat(studies.rows.map { it.id }).containsExactly(13L)
        assertThat(studies.deleteCalls).isEqualTo(1)
        assertThat(studies.events.first()).isEqualTo("lock:7")
        assertThat(studies.subtreeLimit).isEqualTo(129)
        assertThat(questions.completedRows.single().answer).isEqualTo("Prior answer")
        assertThat(questions.completedRows.single().score).isEqualTo(85)
        assertThat(questions.softDeletedQuestionIds).isEmpty()
    }

    @Test
    fun `new descendant overflow cycle or foreign descendant prevents a guarded delete`(): Unit = runBlocking {
        studies.rows += study(10, "Root")
        studies.rows += study(11, "Child").apply { parentStudyId = 10 }
        val changed = runCatching { service.deleteStudy(principal, 10, listOf(10L)) }.exceptionOrNull() as ApiException
        assertThat(changed.code).isEqualTo(ApiErrorCode.STUDY_TREE_CHANGED)
        studies.rows.single { it.id == 10L }.parentStudyId = 11
        val cyclic = runCatching { service.deleteStudy(principal, 10, listOf(10L, 11L)) }.exceptionOrNull() as ApiException
        assertThat(cyclic.code).isEqualTo(ApiErrorCode.STUDY_TREE_CHANGED)
        studies.rows.single { it.id == 10L }.parentStudyId = null
        studies.rows.single { it.id == 11L }.userId = 99
        val foreign = runCatching { service.deleteStudy(principal, 10, listOf(10L)) }.exceptionOrNull() as ApiException
        assertThat(foreign.code).isEqualTo(ApiErrorCode.STUDY_TREE_CHANGED)
        studies.rows.clear()
        studies.rows += study(1, "Big root")
        (2L..129L).forEach { id -> studies.rows += study(id, "Child $id").apply { parentStudyId = 1 } }
        val overflow = runCatching { service.deleteStudy(principal, 1, (1L..128L).toList()) }.exceptionOrNull() as ApiException
        assertThat(overflow.code).isEqualTo(ApiErrorCode.STUDY_TREE_CHANGED)
        assertThat(studies.deleteCalls).isZero()
        assertThat(studies.rows).hasSize(129)
    }

    @Test
    fun `guarded deletion rejects malformed confirmations before locking and reports a zero-row deletion`(): Unit = runBlocking {
        studies.rows += study(10, "Root")
        listOf(emptyList(), listOf(10L, 10L), listOf(0L, 10L), listOf(11L), (1L..129L).toList()).forEach { expected ->
            val invalid = runCatching { service.deleteStudy(principal, 10, expected) }.exceptionOrNull() as ApiException
            assertThat(invalid.code).isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        }
        assertThat(studies.events).isEmpty()
        studies.forceDeleteZero = true
        val vanished = runCatching { service.deleteStudy(principal, 10, listOf(10L)) }.exceptionOrNull() as ApiException
        assertThat(vanished.code).isEqualTo(ApiErrorCode.STUDY_SETTINGS_MISSING)
        assertThat(questions.softDeletedQuestionIds).isEmpty()
    }

    private fun unrelatedMetadata(row: StudyEntity): List<Any?> = listOf(
        row.id, row.deviceId, row.userId, row.parentStudyId, row.sortOrder, row.intervalMinutes,
        row.enabled, row.activeForQuestions, row.notificationSound, row.customPrompt, row.openaiModel,
        row.maxHistoryCount, row.nextDueAt, row.scheduleClaimedUntil, row.lastSentAt, row.lastError, row.createdAt,
    )

    @Test
    fun `curriculum batch inherits original root level and stores explicit leaves only on new nodes`(): Unit = runBlocking {
        val root = study(11, "Root").apply { difficultyLevel = 8 }
        val parent = study(12, "Selected branch").apply { parentStudyId = 11; difficultyLevel = 3 }
        val existing = study(13, "Existing leaf").apply {
            parentStudyId = 12; difficultyLevel = 2; curriculumTerminal = true
        }
        studies.rows += listOf(root, parent, existing)
        val beforeExisting = completeStudyState(existing)
        val command = CreateStudyTopicsCommand(
            topics = listOf("Existing leaf", " New leaf ", "Open branch", "Unexpanded"),
            difficultyLevel = 5,
            curriculumTerminalByTopic = mapOf("Existing leaf" to false, " new   leaf " to true, "Open branch" to false),
            inheritRootDifficulty = true,
        )

        val response = service.createStudyTopics(principal, parent.id, command)
        val replay = service.createStudyTopics(principal, parent.id, command.copy(
            curriculumTerminalByTopic = mapOf("New leaf" to false, "Unexpanded" to true),
        ))

        assertThat(response.topics.map { it.difficultyLevel }).containsExactly(2, 8, 8, 8)
        assertThat(response.topics.map { it.curriculumTerminal }).containsExactly(true, true, false, false)
        assertThat(replay.topics.map { it.curriculumTerminal }).containsExactly(true, true, false, false)
        assertThat(replay.topics.map { it.created }).containsOnly(false)
        assertThat(completeStudyState(existing)).isEqualTo(beforeExisting)
        assertThat(studies.saveCalls).isEqualTo(3)
        assertThat(questions.pendingRows).isEmpty()
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
        assertThat(service.study(principal, response.topics[1].id, "ko").curriculumTerminal).isTrue()
    }

    @Test
    fun `single voice child inherits original root difficulty while ordinary creation retains its explicit level`(): Unit = runBlocking {
        studies.rows += study(11, "Root").apply { difficultyLevel = 8 }
        studies.rows += study(12, "Branch").apply { parentStudyId = 11; difficultyLevel = 3 }

        val voice = service.createStudyTopicWithOutcome(principal, 12,
            CreateStudyTopicCommand("Voice child", difficultyLevel = 5, inheritRootDifficulty = true))
        val ordinary = service.createStudyTopicWithOutcome(principal, 12,
            CreateStudyTopicCommand("App child", difficultyLevel = 6))

        assertThat(voice.difficultyLevel).isEqualTo(8)
        assertThat(ordinary.difficultyLevel).isEqualTo(6)
        assertThat(voice.curriculumTerminal).isFalse()
        assertThat(ordinary.curriculumTerminal).isFalse()
        assertThat(studies.rows.single { it.id == 12L }.difficultyLevel).isEqualTo(3)
    }

    @Test
    fun `explicit child creation refines terminal parent only after a new child succeeds`(): Unit = runBlocking {
        val parent = study(11, "Terminal").apply { curriculumTerminal = true }
        studies.rows += parent
        val existing = study(12, "Legacy child").apply { parentStudyId = 11; curriculumTerminal = true }
        studies.rows += existing
        studies.rows += study(13, "Conflicting root")
        val beforeParent = completeStudyState(parent)
        val before = completeStudyState(existing)

        val replay = service.createStudyTopics(principal, 11, CreateStudyTopicsCommand(listOf("Legacy child")))
        val batchFailure = runCatching {
            service.createStudyTopics(principal, 11, CreateStudyTopicsCommand(listOf("New child", "Conflicting root")))
        }.exceptionOrNull() as ApiException

        assertThat(replay.topics.single().created).isFalse()
        assertThat(replay.topics.single().curriculumTerminal).isTrue()
        assertThat(completeStudyState(existing)).isEqualTo(before)
        assertThat(batchFailure.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(studies.saveCalls).isZero()
        assertThat(completeStudyState(parent)).isEqualTo(beforeParent)
        val added = service.createStudyTopicWithOutcome(principal, 11, CreateStudyTopicCommand("New child"))
        assertThat(added.created).isTrue()
        assertThat(parent.curriculumTerminal).isFalse()
        assertThat(studies.saveCalls).isEqualTo(2) // The child plus its explicitly refined parent.
        val afterParent = completeStudyState(parent)
        service.createStudyTopicWithOutcome(principal, 11, CreateStudyTopicCommand("New child"))
        assertThat(completeStudyState(parent)).isEqualTo(afterParent)
        assertThat(studies.saveCalls).isEqualTo(2)
        assertThat(service.updateStudy(principal, 12, UpdateStudyCommand(topic = "Renamed leaf")).curriculumTerminal).isTrue()
        assertThat(studies.rows).hasSize(4)
    }

    @Test
    fun `ordinary root upsert and create-only replay preserve terminal metadata omitted from app requests`(): Unit = runBlocking {
        studies.rows += study(11, "Known leaf").apply { curriculumTerminal = true }
        val upserted = service.createStudy(principal, CreateStudyCommand("Known leaf", difficultyLevel = 9))
        val replay = service.createRootStudy(principal, CreateRootStudyCommand("Known leaf", difficultyLevel = 2))
        val newRoot = service.createRootStudy(principal, CreateRootStudyCommand("Unexpanded root"))

        assertThat(upserted.curriculumTerminal).isTrue()
        assertThat(upserted.difficultyLevel).isEqualTo(9)
        assertThat(replay.curriculumTerminal).isTrue()
        assertThat(replay.difficultyLevel).isEqualTo(9)
        assertThat(replay.created).isFalse()
        assertThat(newRoot.curriculumTerminal).isFalse()
        assertThat(studies.rows.single { it.id == 11L }.curriculumTerminal).isTrue()
    }

    @Test
    fun `ambiguous normalized curriculum metadata rejects a whole batch before mutation`(): Unit = runBlocking {
        studies.rows += study(11, "Root")
        listOf(mapOf("Leaf" to true, " leaf " to false), mapOf("Unselected leaf" to true)).forEach { metadata ->
            val failure = runCatching {
                service.createStudyTopics(principal, 11, CreateStudyTopicsCommand(
                    listOf("Leaf"), curriculumTerminalByTopic = metadata,
                ))
            }.exceptionOrNull() as ApiException
            assertThat(failure.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        }
        assertThat(studies.events).isEmpty()
        assertThat(studies.saveCalls).isZero()
    }

    private fun completeStudyState(row: StudyEntity): List<Any?> = listOf(
        row.id, row.deviceId, row.userId, row.parentStudyId, row.sortOrder, row.topic,
        row.difficultyLevel, row.intervalMinutes, row.enabled, row.activeForQuestions,
        row.notificationSound, row.customPrompt, row.openaiModel, row.maxHistoryCount,
        row.nextDueAt, row.scheduleClaimedUntil, row.lastSentAt, row.lastError,
        row.createdAt, row.updatedAt, row.curriculumTerminal,
    )

    private fun study(id: Long, topic: String) = StudyEntity(
        id = id,
        deviceId = principal.deviceId,
        userId = principal.userId,
        topic = topic,
        difficultyLevel = 5,
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
    )

    private fun pendingQuestion(id: Long, studyId: Long, topic: String) = QuestionEntity(
        id = id,
        deviceId = principal.deviceId,
        userId = principal.userId,
        studyId = studyId,
        question = "Question $topic",
        topic = topic,
        difficultyLevel = 5,
        scheduledFor = Instant.parse("2026-06-10T00:00:00Z"),
        sentAt = Instant.parse("2026-06-10T00:00:00Z"),
        status = QuestionStatus.UNGRADED,
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
    )

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        val events = mutableListOf<String>()
        var ownerAvailable = true
        var beforeMetadataUpdate: (() -> Unit)? = null
        var metadataUpdateCalls = 0
        var deleteCalls = 0
        var forceDeleteZero = false
        var subtreeLimit: Int? = null
        var findByParentCalls = 0
        var findByUserCalls = 0
        var findByUserAndQueryCalls = 0
        var saveCalls = 0
        var lastChildQuery: String? = null
        var lastChildPageable: Pageable? = null
        override suspend fun save(entity: StudyEntity): StudyEntity {
            events += "save"
            saveCalls += 1
            if (entity.id == 0L) {
                entity.id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
                rows += entity
            }
            return entity
        }
        override suspend fun lockMutationOwner(userId: Long): Boolean {
            events += "lock:$userId"
            return ownerAvailable
        }
        override suspend fun updateTopicMetadata(id: Long, userId: Long, topic: String?, difficultyLevel: Int?, now: Instant): StudyEntity? {
            events += "patch"
            metadataUpdateCalls += 1
            beforeMetadataUpdate?.invoke()
            return rows.firstOrNull { it.id == id && it.userId == userId }?.also {
                topic?.let { value -> it.topic = value }
                difficultyLevel?.let { value -> it.difficultyLevel = value }
                it.updatedAt = now
            }
        }
        override suspend fun updateTopicMetadataIfCurrent(
            id: Long,
            userId: Long,
            topic: String?,
            difficultyLevel: Int?,
            expectedParentStudyId: Long?,
            expectedTopic: String,
            expectedDifficultyLevel: Int,
            now: Instant,
        ): StudyEntity? {
            events += "conditional-patch"
            metadataUpdateCalls += 1
            beforeMetadataUpdate?.invoke()
            val row = rows.firstOrNull { it.id == id && it.userId == userId } ?: return null
            if (row.parentStudyId != expectedParentStudyId || row.topic != expectedTopic ||
                row.difficultyLevel != expectedDifficultyLevel
            ) return null
            topic?.let { row.topic = it }
            difficultyLevel?.let { row.difficultyLevel = it }
            row.updatedAt = now
            return row
        }
        override suspend fun findSubtreeIdsForMutation(userId: Long, studyId: Long, limit: Int): List<Long>? {
            subtreeLimit = limit
            events += "subtree"
            if (rows.none { it.id == studyId && it.userId == userId }) return emptyList()
            val seen = linkedSetOf(studyId)
            val queue = ArrayDeque<Long>().also { it.add(studyId) }
            while (queue.isNotEmpty() && seen.size < limit) {
                val parent = queue.removeFirst()
                for (child in rows.filter { it.parentStudyId == parent }.take(limit - seen.size)) {
                    if (child.userId != userId || !seen.add(child.id)) return null
                    queue.add(child.id)
                }
            }
            return seen.toList()
        }
        override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long {
            events += "delete"
            deleteCalls += 1
            if (forceDeleteZero || rows.none { it.id == id && it.userId == userId }) return 0L
            val descendants = findSubtreeIdsForMutation(userId, id, 129).orEmpty().toSet()
            rows.removeAll { it.id in descendants && it.userId == userId }
            return 1L
        }
        override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? {
            events += "owned-read"
            return rows.firstOrNull { it.id == id && it.userId == userId }
        }
        override suspend fun findByUserIdAndParentStudyIdAndTopic(
            userId: Long,
            parentStudyId: Long?,
            topic: String,
        ): StudyEntity? = rows.firstOrNull {
            it.userId == userId && it.parentStudyId == parentStudyId && it.topic == topic
        }
        override suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = rows.firstOrNull { it.userId == userId && it.topic == topic }
        override suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> =
            rows.filter { it.userId == userId && it.topic in topics }
        override suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> {
            events += "owner-list"
            findByUserCalls += 1
            return PageImpl(rows.filter { it.userId == userId }, pageable, rows.count { it.userId == userId }.toLong())
        }
        override suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> {
            findByUserAndQueryCalls += 1
            return PageImpl(rows.filter { it.userId == userId && it.topic.contains(query, ignoreCase = true) }, pageable, rows.count { it.userId == userId }.toLong())
        }
        override suspend fun findByUserIdAndParentStudyId(
            userId: Long,
            parentStudyId: Long,
            query: String?,
            pageable: Pageable,
        ): Page<StudyEntity> {
            findByParentCalls += 1
            lastChildQuery = query
            lastChildPageable = pageable
            val matching = rows.filter { row ->
                row.userId == userId && row.parentStudyId == parentStudyId &&
                    (query == null || listOf(row.topic, row.customPrompt, row.openaiModel)
                        .any { it.contains(query, ignoreCase = true) })
            }.sortedWith(compareBy<StudyEntity> { it.sortOrder }.thenBy { it.id })
            return PageImpl(
                matching.drop(pageable.offset.toInt()).take(pageable.pageSize),
                pageable,
                matching.size.toLong(),
            )
        }
        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class FakeQuestionPort : QuestionPort {
        val pendingRows = mutableListOf<QuestionEntity>()
        val completedRows = mutableListOf<QuestionEntity>()
        val softDeletedQuestionIds = mutableListOf<Long>()
        var findPendingByStudyIdCalls = 0
        var findLatestPendingByStudyIdsCalls = 0
        var findLatestCompletedCalls = 0
        override suspend fun save(entity: QuestionEntity): QuestionEntity = entity
        override suspend fun findQuestionById(id: Long): QuestionEntity? = null
        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> {
            findPendingByStudyIdCalls += 1
            return PageImpl(pendingRows.filter { it.studyId == studyId }.sortedByDescending { it.createdAt }.take(1), pageable, 1)
        }
        override suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> {
            findLatestPendingByStudyIdsCalls += 1
            return pendingRows
                .filter { it.studyId in studyIds }
                .groupBy { it.studyId }
                .values
                .mapNotNull { rows -> rows.maxWithOrNull(compareBy<QuestionEntity> { it.createdAt }.thenBy { it.id }) }
        }
        override suspend fun findLatestCompletedByStudyIdAndUserId(studyId: Long, userId: Long): QuestionEntity? {
            findLatestCompletedCalls += 1
            return completedRows
                .filter { it.studyId == studyId && it.userId == userId }
                .maxWithOrNull(compareBy<QuestionEntity> { it.answeredAt }.thenBy { it.createdAt }.thenBy { it.id })
        }
        override suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun countPendingForStudy(studyId: Long): Long = pendingRows.count { it.studyId == studyId }.toLong()
        override suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> =
            pendingRows
                .filter { it.studyId in studyIds }
                .groupingBy { it.studyId!! }
                .eachCount()
                .mapValues { it.value.toLong() }
        override suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override suspend fun softDelete(id: Long, userId: Long, now: Instant): Int {
            softDeletedQuestionIds += id
            return 0
        }
        override suspend fun softDeleteByUserId(userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int = 0
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        override suspend fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override suspend fun findById(id: Long): QuestionStatsEntity? {
            findByIdCalls += 1
            return rows.firstOrNull { it.questionId == id }
        }
        override suspend fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity> {
            findAllByIdsCalls += 1
            return rows.filter { it.questionId in ids }
        }
        override suspend fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun incrementLike(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

}
