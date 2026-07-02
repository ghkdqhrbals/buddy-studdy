package com.buddystudy.backend.study.adapter.inbound.scheduler

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.scheduler.application.model.JobRunStatus
import com.buddystudy.backend.scheduler.application.model.JobTriggerType
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRun
import com.buddystudy.backend.scheduler.application.model.ScheduledJobRunPageResponse
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJob
import com.buddystudy.backend.scheduler.application.port.inbound.ManagedJobExecutionUseCase
import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.backend.study.application.service.ScheduledQuestionService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.util.Optional

class QuestionSchedulerTest {
    private val studies = FakeStudyPort()
    private val users = FakeUserPort()
    private val questions = FakeQuestionPort()
    private val questionStats = FakeQuestionStatsPort()
    private val questionEmbeddings = FakeQuestionEmbeddingPort()
    private val questionCoverage = FakeQuestionCoveragePort()
    private val questionCreatedPublisher = FakeQuestionCreatedPublisher()
    private val openAI = FakeOpenAI()
    private val notifications = FakeNotificationPublisher()
    private val memberships = FakeQuestionMembershipPort()
    private val properties = BuddyStudyProperties(
        scheduler = BuddyStudyProperties.Scheduler(enabled = true, maxPendingPerStudy = 1),
        openai = BuddyStudyProperties.OpenAI(apiKey = "sk-test", model = "gpt-5.4"),
    )
    private val cipher = KeyCipher(BuddyStudyProperties().apply { crypto.masterKey = "test-key" })
    private val scheduler = ScheduledQuestionService(
        properties = properties,
        studies = studies,
        users = users,
        questions = questions,
        questionStats = questionStats,
        questionEmbeddings = questionEmbeddings,
        questionCoverage = questionCoverage,
        questionCreatedPublisher = questionCreatedPublisher,
        notifications = notifications,
        openAI = openAI,
        questionKeys = OpenAIQuestionKeyProvider(properties, cipher, memberships),
        questionPrompts = QuestionPromptProvider(),
        questionDiversity = QuestionDiversityPolicy(),
    )

    @Test
    fun `question scheduler runs through managed job executor`() {
        val useCase = FakeRunQuestionScheduleUseCase()
        val job = QuestionScheduleJob(useCase)
        val jobs = FakeManagedJobExecutionUseCase()
        val adapter = QuestionScheduler(jobs, job)

        adapter.runScheduled()

        assertThat(jobs.executedJobNames).containsExactly("question-schedule")
        assertThat(jobs.triggerTypes).containsExactly(JobTriggerType.SCHEDULED)
        assertThat(useCase.calls).isEqualTo(1)
    }

    @Test
    fun `scheduled run uses same study and same topic history for each study`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        studies.rows += study(id = 101, userId = 7, topic = "Swift", now = now)
        studies.rows += study(id = 102, userId = 7, topic = "Kotlin", now = now)
        questions.visibleRows += QuestionEntity(
            id = 900,
            deviceId = "dev-1",
            userId = 7,
            question = "Previous question",
            topic = "Swift",
            difficultyLevel = 5,
            scheduledFor = now.minusSeconds(60),
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60),
        )

        scheduler.runDueQuestions()

        assertThat(questions.savedRows).hasSize(2)
        assertThat(questionCreatedPublisher.questionIds).containsExactly(1, 2)
        assertThat(notifications.commands).hasSize(2)
        assertThat(notifications.commands).allSatisfy { command ->
            assertThat(command.shouldPush).isTrue()
            assertThat(command.type).isEqualTo("STUDY_QUESTION")
        }
        assertThat(studies.rows.map { it.nextDueAt }).allSatisfy { assertThat(it).isAfter(now) }
        assertThat(users.findByIdCalls).isEqualTo(1)
        assertThat(questions.findVisibleByUserCalls).isZero()
        assertThat(questions.findRecentQuestionTextsByStudyIdAndTopicCalls).isEqualTo(2)
        assertThat(questions.findRecentQuestionTextsByUserIdAndTopicCalls).isEqualTo(2)
        assertThat(openAI.recentArguments).allSatisfy { recent ->
            if (recent.isNotEmpty()) {
                assertThat(recent).containsExactly("Previous question")
            }
        }
    }

    @Test
    fun `scheduled run uses batch pending lookup when per study pending limit is one`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        studies.rows += study(id = 101, userId = 7, topic = "Swift", now = now)
        studies.rows += study(id = 102, userId = 7, topic = "Kotlin", now = now)
        questions.pendingRows += pendingQuestion(id = 901, studyId = 101, topic = "Swift", now = now)

        scheduler.runDueQuestions()

        assertThat(questions.savedRows.map { it.studyId }).containsExactly(102)
        assertThat(notifications.commands.map { it.body }).containsExactly("Question for Kotlin")
        assertThat(questions.countPendingForStudyCalls).isZero()
        assertThat(questions.countPendingByStudyIdsCalls).isEqualTo(1)
        assertThat(questions.findLatestPendingByStudyIdsCalls).isZero()
    }

    @Test
    fun `scheduled run drains all due studies across multiple batches`() {
        properties.scheduler.batchSize = 2
        val now = Instant.parse("2026-06-10T00:00:00Z")
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        studies.rows += study(id = 101, userId = 7, topic = "Swift", now = now)
        studies.rows += study(id = 102, userId = 7, topic = "Kotlin", now = now)
        studies.rows += study(id = 103, userId = 7, topic = "Redis", now = now)
        studies.rows += study(id = 104, userId = 7, topic = "Kafka", now = now)
        studies.rows += study(id = 105, userId = 7, topic = "Postgres", now = now)

        scheduler.runDueQuestions()

        assertThat(questions.savedRows.map { it.studyId }).containsExactly(101, 102, 103, 104, 105)
        assertThat(notifications.commands.map { it.body }).containsExactly(
            "Question for Swift",
            "Question for Kotlin",
            "Question for Redis",
            "Question for Kafka",
            "Question for Postgres",
        )
        assertThat(studies.claimDueCalls).isEqualTo(4)
        assertThat(questions.countPendingByStudyIdsCalls).isEqualTo(3)
    }

    @Test
    fun `scheduled run drains overdue study state without recovery jobs`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        studies.rows += study(id = 101, userId = 7, topic = "Swift", now = now)

        scheduler.runDueQuestions()

        assertThat(questions.savedRows.map { it.studyId }).containsExactly(101)
        assertThat(studies.rows.single().lastSentAt).isNotNull()
        assertThat(studies.rows.single().nextDueAt).isAfter(now)
    }

    @Test
    fun `scheduled run uses batch pending counts when per study pending limit is above one`() {
        properties.scheduler.maxPendingPerStudy = 2
        val now = Instant.parse("2026-06-10T00:00:00Z")
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        studies.rows += study(id = 101, userId = 7, topic = "Swift", now = now)
        studies.rows += study(id = 102, userId = 7, topic = "Kotlin", now = now)
        questions.pendingRows += pendingQuestion(id = 901, studyId = 101, topic = "Swift", now = now)
        questions.pendingRows += pendingQuestion(id = 902, studyId = 101, topic = "Swift", now = now.plusSeconds(1))

        scheduler.runDueQuestions()

        assertThat(questions.savedRows.map { it.studyId }).containsExactly(102)
        assertThat(questions.countPendingForStudyCalls).isZero()
        assertThat(questions.countPendingByStudyIdsCalls).isEqualTo(1)
    }

    @Test
    fun `scheduled run backs off briefly when study already has pending question`() {
        val dueAt = Instant.now().minusSeconds(1)
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        val study = study(id = 101, userId = 7, topic = "Swift", now = dueAt)
        studies.rows += study
        questions.pendingRows += pendingQuestion(id = 901, studyId = 101, topic = "Swift", now = dueAt)

        scheduler.runDueQuestions()

        assertThat(questions.savedRows).isEmpty()
        assertThat(notifications.commands).isEmpty()
        assertThat(study.lastError).contains("Pending question limit reached")
        assertThat(Duration.between(Instant.now(), study.nextDueAt).seconds).isBetween(250, 310)
    }

    @Test
    fun `scheduled run backs off longer when api key is missing`() {
        properties.openai.apiKey = ""
        val dueAt = Instant.now().minusSeconds(1)
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        val study = study(id = 101, userId = 7, topic = "Swift", now = dueAt)
        studies.rows += study

        scheduler.runDueQuestions()

        assertThat(questions.savedRows).isEmpty()
        assertThat(openAI.generateQuestionCalls).isZero()
        assertThat(study.lastError).isEqualTo("OpenAI API key is not configured.")
        assertThat(Duration.between(Instant.now(), study.nextDueAt).seconds).isBetween(1_750, 1_810)
    }

    @Test
    fun `scheduled run stops using system key after monthly tier question limit`() {
        val now = Instant.parse("2026-06-10T00:00:00Z")
        val user = UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        users.rows += user
        memberships.usedCount = 29
        studies.rows += study(id = 101, userId = 7, topic = "Swift", now = now)
        studies.rows += study(id = 102, userId = 7, topic = "Kotlin", now = now)

        scheduler.runDueQuestions()

        assertThat(questions.savedRows.map { it.studyId }).containsExactly(101)
        assertThat(memberships.usedCount).isEqualTo(30)
        assertThat(studies.rows.single { it.id == 102L }.lastError)
            .isEqualTo("Monthly question limit reached. Add your OpenAI API key to continue.")
    }

    @Test
    fun `scheduled run backs off on openai failure without enqueueing push`() {
        val dueAt = Instant.now().minusSeconds(1)
        users.rows += UserEntity(id = 7, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        val study = study(id = 101, userId = 7, topic = "Swift", now = dueAt)
        studies.rows += study
        openAI.failure = IllegalStateException("OpenAI unavailable")

        scheduler.runDueQuestions()

        assertThat(questions.savedRows).isEmpty()
        assertThat(notifications.commands).isEmpty()
        assertThat(study.lastError).isEqualTo("OpenAI unavailable")
        assertThat(memberships.usedCount).isZero()
        assertThat(memberships.consumeCalls).isEqualTo(1)
        assertThat(memberships.refundCalls).isEqualTo(1)
        assertThat(Duration.between(Instant.now(), study.nextDueAt).seconds).isBetween(550, 610)
    }

    private fun study(id: Long, userId: Long, topic: String, now: Instant) = StudyEntity(
        id = id,
        deviceId = "dev-$userId",
        userId = userId,
        topic = topic,
        difficultyLevel = 5,
        intervalMinutes = 15,
        nextDueAt = now.minusSeconds(1),
        createdAt = now.minusSeconds(120),
        updatedAt = now.minusSeconds(120),
    )

    private fun pendingQuestion(id: Long, studyId: Long, topic: String, now: Instant) = QuestionEntity(
        id = id,
        deviceId = "dev-7",
        userId = 7,
        studyId = studyId,
        question = "Pending $topic",
        topic = topic,
        difficultyLevel = 5,
        scheduledFor = now.minusSeconds(60),
        sentAt = now.minusSeconds(60),
        status = "ungraded",
        createdAt = now.minusSeconds(60),
        updatedAt = now.minusSeconds(60),
    )

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        override fun save(entity: StudyEntity): StudyEntity = entity
        override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
            rows.firstOrNull { it.id == id && it.userId == userId }
        override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = null
        override fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> =
            rows.filter { it.userId == userId && it.topic in topics }
        override fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> = Page.empty()
        var claimDueCalls = 0
        override fun claimDue(now: Instant, limit: Int): List<StudyEntity> {
            claimDueCalls += 1
            val due = rows
                .filter { it.enabled && it.nextDueAt?.isAfter(now) == false }
                .sortedWith(compareBy<StudyEntity> { it.nextDueAt }.thenBy { it.id })
                .take(limit)
            due.forEach { it.nextDueAt = now.plusSeconds(86_400) }
            return due
        }
    }

    private class FakeRunQuestionScheduleUseCase : RunQuestionScheduleUseCase {
        var calls = 0
        override fun runDueQuestions() {
            calls += 1
        }
    }

    private class FakeManagedJobExecutionUseCase : ManagedJobExecutionUseCase {
        val executedJobNames = mutableListOf<String>()
        val triggerTypes = mutableListOf<JobTriggerType>()

        override fun execute(
            job: ManagedJob,
            triggerType: JobTriggerType,
            retryOfRunId: Long?,
            createdBy: String,
        ): ScheduledJobRun {
            executedJobNames += job.name
            triggerTypes += triggerType
            val summary = job.run()
            return ScheduledJobRun(
                id = executedJobNames.size.toLong(),
                jobName = job.name,
                triggerType = triggerType,
                status = JobRunStatus.SUCCESS,
                startedAt = Instant.EPOCH,
                finishedAt = Instant.EPOCH,
                summary = summary,
                retryOfRunId = retryOfRunId,
                createdBy = createdBy,
            )
        }

        override fun findRuns(jobName: String?, limit: Int, offset: Int): ScheduledJobRunPageResponse =
            ScheduledJobRunPageResponse(emptyList(), 0, limit, offset)
    }

    private class FakeUserPort : UserPort {
        val rows = mutableListOf<UserEntity>()
        var findByIdCalls = 0
        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(rows.firstOrNull { it.id == id })
        }
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = rows.filter { it.id in ids.toSet() }.toMutableList()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionMembershipPort : QuestionMembershipPort {
        var tier: String? = null
        var usedCount = 0
        var consumeCalls = 0
        var refundCalls = 0
        override fun activeTierCodeForUser(userId: Long): String? = tier
        override fun tryConsumeMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean {
            consumeCalls += 1
            if (usedCount >= limit) return false
            usedCount += 1
            return true
        }
        override fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant) {
            refundCalls += 1
            if (usedCount > 0) usedCount -= 1
        }
    }

    private class FakeQuestionPort : QuestionPort {
        val visibleRows = mutableListOf<QuestionEntity>()
        val pendingRows = mutableListOf<QuestionEntity>()
        val savedRows = mutableListOf<QuestionEntity>()
        var findVisibleByUserCalls = 0
        var findRecentQuestionTextsByStudyIdAndTopicCalls = 0
        var findRecentQuestionTextsByUserIdAndTopicCalls = 0
        var countPendingForStudyCalls = 0
        var countPendingByStudyIdsCalls = 0
        var findLatestPendingByStudyIdsCalls = 0
        override fun save(entity: QuestionEntity): QuestionEntity {
            entity.id = (savedRows.size + 1).toLong()
            savedRows += entity
            return entity
        }
        override fun findQuestionById(id: Long): Optional<QuestionEntity> = Optional.empty()
        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> {
            findLatestPendingByStudyIdsCalls += 1
            return pendingRows
                .filter { it.studyId in studyIds }
                .groupBy { it.studyId }
                .values
                .mapNotNull { rows -> rows.maxWithOrNull(compareBy<QuestionEntity> { it.createdAt }.thenBy { it.id }) }
        }
        override fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> {
            findVisibleByUserCalls += 1
            return PageImpl(visibleRows.filter { it.userId == userId }, pageable, visibleRows.count { it.userId == userId }.toLong())
        }
        override fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> {
            findRecentQuestionTextsByStudyIdAndTopicCalls += 1
            return visibleRows
                .filter { it.studyId == studyId && it.topic.equals(topic, ignoreCase = true) && it.deletedAt == null }
                .sortedByDescending { it.createdAt }
                .map { it.question }
                .take(pageable.pageSize)
        }
        override fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> {
            findRecentQuestionTextsByUserIdAndTopicCalls += 1
            return visibleRows
                .filter { it.userId == userId && it.topic.equals(topic, ignoreCase = true) && it.deletedAt == null }
                .sortedByDescending { it.createdAt }
                .map { it.question }
                .take(pageable.pageSize)
        }
        override fun countPendingForStudy(studyId: Long): Long {
            countPendingForStudyCalls += 1
            return pendingRows.count { it.studyId == studyId }.toLong()
        }
        override fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> {
            countPendingByStudyIdsCalls += 1
            return pendingRows
                .filter { it.studyId in studyIds }
                .groupingBy { it.studyId!! }
                .eachCount()
                .mapValues { it.value.toLong() }
        }
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        override fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override fun findById(id: Long): Optional<QuestionStatsEntity> = Optional.empty()
        override fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity> = emptyList()
        override fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementLike(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeOpenAI : OpenAIPort {
        val recentArguments = mutableListOf<List<String>>()
        var generateQuestionCalls = 0
        var failure: RuntimeException? = null
        override fun validate(apiKey: String) = Unit
        override fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
            generateQuestionCalls += 1
            failure?.let { throw it }
            val recentText = prompt.userPrompt
                .substringAfter("Previously asked questions for this learner and topic: ")
                .substringBefore("\n")
                .split("|")
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "None" }
            recentArguments += recentText
            return GeneratedQuestion("Question for ${prompt.fallbackTopic}", "Hint")
        }
        override fun embedText(apiKey: String, text: String): List<Float> = listOf(0f, 0f, 1f)
        override fun generateQuestionCoverageBlueprint(apiKey: String, model: String, topic: String, level: Int, customPrompt: String): List<OpenAIPort.QuestionCoverageConcept> =
            listOf(
                OpenAIPort.QuestionCoverageConcept(
                    key = "general",
                    name = "General",
                    angles = listOf(OpenAIPort.QuestionCoverageAngle("definition", "Definition")),
                )
            )
        override fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer =
            GradedAnswer(100, true, "Good", "Because")
    }

    private class FakeQuestionEmbeddingPort : QuestionEmbeddingPort {
        val savedRows = mutableListOf<QuestionEmbeddingCandidate>()
        override fun save(questionId: Long, userId: Long, studyId: Long, topic: String, question: String, embedding: List<Float>): QuestionEmbeddingCandidate {
            val row = QuestionEmbeddingCandidate(questionId, question, embedding)
            savedRows += row
            return row
        }

        override fun findRecentByStudyIdAndTopic(studyId: Long, topic: String, limit: Int): List<QuestionEmbeddingCandidate> = emptyList()
    }

    private class FakeQuestionCoveragePort : QuestionCoveragePort {
        override fun ensureCoverage(studyId: Long, topic: String, concepts: List<QuestionCoveragePort.CoverageConceptBlueprint>) = Unit
        override fun selectNext(studyId: Long): QuestionCoverageSelection? = null
        override fun markAsked(selection: QuestionCoverageSelection, now: Instant) = Unit
        override fun markAnswered(conceptId: Long, angleKey: String, score: Int, correct: Boolean, now: Instant) = Unit
    }

    private class FakeNotificationPublisher : PublishNotificationUseCase {
        val commands = mutableListOf<NotificationRequestCommand>()
        override fun publish(command: NotificationRequestCommand): Boolean {
            commands += command
            return true
        }
    }

    private class FakeQuestionCreatedPublisher : QuestionCreatedPublishPort {
        val questionIds = mutableListOf<Long>()
        override fun publishQuestionCreated(questionId: Long, language: String, createdAt: Instant): Boolean {
            questionIds += questionId
            return true
        }
    }
}
