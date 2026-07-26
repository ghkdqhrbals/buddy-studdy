package com.buddystudy.backend.study

import kotlinx.coroutines.runBlocking

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPlan
import com.buddystudy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystudy.backend.study.application.port.outbound.QuestionQuotaStatus
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushOutboxAppendPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.backend.study.application.service.QuestionCreationWriteService
import com.buddystudy.backend.study.application.service.StudyRecordWriteService
import com.buddystudy.backend.study.application.service.StudyService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.time.YearMonth
import java.util.Optional

class StudyServiceTest {
    private val questions = FakeQuestionPort()
    private val questionStats = FakeQuestionStatsPort()
    private val users = FakeUserPort()
    private val openAI = FakeOpenAI()
    private val questionEmbeddings = FakeQuestionEmbeddingPort()
    private val questionCoverage = FakeQuestionCoveragePort()
    private val serviceStudies = FakeStudyPort()
    private val memberships = FakeQuestionMembershipPort()
    private val pushOutbox = FakeQuestionPushOutbox()
    private val properties = BuddyStudyProperties().apply { openai.apiKey = "test-api-key" }
    private val cipher = KeyCipher(BuddyStudyProperties().apply { crypto.masterKey = "test-key" })
    private val questionKeys = OpenAIQuestionKeyProvider(properties, memberships)
    private val service = StudyService(
        properties = properties,
        studies = serviceStudies,
        questions = questions,
        questionStats = questionStats,
        openAI = openAI,
        questionEmbeddings = questionEmbeddings,
        questionCoverage = questionCoverage,
        users = users,
        cipher = cipher,
        questionKeys = questionKeys,
        questionPrompts = QuestionPromptProvider(),
        questionDiversity = QuestionDiversityPolicy(),
        questionWriter = QuestionCreationWriteService(
            questions = questions,
            questionStats = questionStats,
            questionEmbeddings = questionEmbeddings,
            questionCoverage = questionCoverage,
            questionKeys = questionKeys,
            notificationOutbox = FakeNotificationOutbox(),
            pushOutbox = pushOutbox,
        ),
        recordWriter = StudyRecordWriteService(questions, questionCoverage),
        outboxPublisher = NoOpOutboxPublisher(),
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `records load question stats in one batch`(): Unit = runBlocking {
        questions.visibleRows += gradedQuestion(id = 101, topic = "Swift")
        questions.visibleRows += gradedQuestion(id = 102, topic = "Kotlin")
        questionStats.rows += QuestionStatsEntity(questionId = 101, likeCount = 3, commentCount = 2, viewCount = 9)
        questionStats.rows += QuestionStatsEntity(questionId = 102, likeCount = 4, commentCount = 1, viewCount = 8)

        val response = service.records(principal, limit = 20, offset = 0, query = null)

        assertThat(response.records.map { it.id }).containsExactly("101", "102")
        assertThat(response.records.map { it.likeCount }).containsExactly(3, 4)
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
    }

    @Test
    fun `pending records load question stats in one batch`(): Unit = runBlocking {
        questions.pendingRows += pendingQuestion(id = 201, topic = "Redis")
        questions.pendingRows += pendingQuestion(id = 202, topic = "Postgres")
        questionStats.rows += QuestionStatsEntity(questionId = 201, viewCount = 6)
        questionStats.rows += QuestionStatsEntity(questionId = 202, viewCount = 7)

        val response = service.pending(principal, limit = 20, offset = 0)

        assertThat(response.records.map { it.id }).containsExactly("201", "202")
        assertThat(response.records.map { it.viewCount }).containsExactly(6, 7)
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
    }

    @Test
    fun `skip reads question stats only for final response`(): Unit = runBlocking {
        questions.visibleRows += pendingQuestion(id = 301, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 301, viewCount = 5)

        val response = service.skip(principal, id = 301)

        assertThat(response.id).isEqualTo("301")
        assertThat(response.viewCount).isEqualTo(5)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
    }

    @Test
    fun `graded answer loads user and question stats only once`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        questions.visibleRows += pendingQuestion(id = 501, topic = "Kotlin")
        questionStats.rows += QuestionStatsEntity(questionId = 501, viewCount = 5)

        val response = service.answer(principal, recordId = 501, answer = "My answer", grade = true)

        assertThat(response.id).isEqualTo("501")
        assertThat(response.gradingResult?.score).isEqualTo(100)
        assertThat(openAI.gradeCalls).isEqualTo(1)
        assertThat(users.findByIdCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
    }

    @Test
    fun `delete soft deletes graded record`(): Unit = runBlocking {
        questions.visibleRows += gradedQuestion(id = 601, topic = "Redis")

        service.delete(principal, id = 601)

        assertThat(questions.visibleRows.single { it.id == 601L }.deletedAt).isNotNull()
    }

    @Test
    fun `create question reuses loaded user for search sync`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        serviceStudies.rows += StudyEntity(
            id = 77,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Kotlin",
            difficultyLevel = 6,
            intervalMinutes = 15,
            customPrompt = "Keep it short.",
            openaiModel = "gpt-5.4",
        )

        val response = service.createQuestion(principal, studyId = 77)

        assertThat(response.question.question).isEqualTo("Question")
        assertThat(openAI.generateCalls).isEqualTo(1)
        assertThat(users.findByIdCalls).isEqualTo(1)
        assertThat(pushOutbox.requests.single().recordId).isEqualTo(response.id.toLong())
        assertThat(pushOutbox.requests.single().topic).isEqualTo("Kotlin")
    }

    @Test
    fun `child topic question is stored against the requested topic study`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        serviceStudies.rows += StudyEntity(
            id = 90,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Backend",
            difficultyLevel = 5,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )
        serviceStudies.rows += StudyEntity(
            id = 91,
            deviceId = principal.deviceId,
            userId = principal.userId,
            parentStudyId = 90,
            topic = "Redis",
            difficultyLevel = 7,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )

        val response = service.createQuestion(principal, studyId = 91)

        assertThat(response.studyId).isEqualTo(91)
        assertThat(questions.visibleRows.single { it.id == response.id.toLong() }.studyId).isEqualTo(91)
        assertThat(questionEmbeddings.savedStudyIds).containsExactly(91)
    }

    @Test
    fun `create question reports a dedicated error when requested topic already has pending question`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        serviceStudies.rows += StudyEntity(
            id = 92,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Redis Streams",
            difficultyLevel = 7,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )
        questions.pendingRows += pendingQuestion(id = 902, topic = "Redis Streams").apply {
            studyId = 92
        }

        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { service.createQuestion(principal, studyId = 92) }
        }
            .isInstanceOf(com.buddystudy.backend.common.application.error.ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS)

        assertThat(openAI.generateCalls).isZero()
    }

    @Test
    fun `create question sends same study and same topic history before openai generation`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        serviceStudies.rows += StudyEntity(
            id = 82,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Redis",
            difficultyLevel = 6,
            intervalMinutes = 15,
            customPrompt = "Ask practical scenarios.",
            openaiModel = "gpt-5.4",
        )
        questions.visibleRows += question(id = 901, topic = "Redis").apply {
            studyId = 82
            question = "How does Redis persistence work?"
        }
        questions.visibleRows += question(id = 902, topic = "Redis").apply {
            studyId = 99
            question = "When should Redis use AOF instead of snapshots?"
        }
        questions.visibleRows += question(id = 903, topic = "Kafka").apply {
            studyId = 82
            question = "What is Kafka consumer lag?"
        }

        service.createQuestion(principal, studyId = 82)

        assertThat(openAI.generatedPrompt?.userPrompt).contains("How does Redis persistence work?")
        assertThat(openAI.generatedPrompt?.userPrompt).contains("When should Redis use AOF instead of snapshots?")
        assertThat(openAI.generatedPrompt?.userPrompt).doesNotContain("What is Kafka consumer lag?")
        assertThat(openAI.generatedPrompt?.userPrompt).contains("Diversity angle:")
        assertThat(openAI.generatedPrompt?.userPrompt).contains("Question format:")
        assertThat(openAI.generatedPrompt?.userPrompt).contains("Reasoning mode:")
        assertThat(questions.findRecentQuestionTextsByStudyIdAndTopicCalls).isEqualTo(1)
        assertThat(questions.findRecentQuestionTextsByUserIdAndTopicCalls).isEqualTo(1)
    }

    @Test
    fun `create question retries and stores embedding when generated question is too similar`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        serviceStudies.rows += StudyEntity(
            id = 83,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Redis",
            difficultyLevel = 6,
            intervalMinutes = 15,
            customPrompt = "Ask practical scenarios.",
            openaiModel = "gpt-5.4",
        )
        questionEmbeddings.rows += QuestionEmbeddingCandidate(
            questionId = 700,
            question = "How does Redis persistence work?",
            embedding = listOf(1f, 0f, 0f),
        )
        openAI.generatedQuestions += GeneratedQuestion("How does Redis persistence work in production?", null)
        openAI.generatedQuestions += GeneratedQuestion("How would you diagnose Redis memory fragmentation?", null)
        openAI.embeddings["How does Redis persistence work in production?"] = listOf(0.99f, 0.01f, 0f)
        openAI.embeddings["How would you diagnose Redis memory fragmentation?"] = listOf(0f, 1f, 0f)

        val response = service.createQuestion(principal, studyId = 83)

        assertThat(response.question.question).isEqualTo("How would you diagnose Redis memory fragmentation?")
        assertThat(openAI.generateCalls).isEqualTo(2)
        assertThat(questionEmbeddings.savedRows).hasSize(1)
        val savedEmbedding = questionEmbeddings.savedRows.single()
        assertThat(savedEmbedding.questionId).isEqualTo(response.id.toLong())
        assertThat(savedEmbedding.embedding).containsExactly(0f, 1f, 0f)
    }

    @Test
    fun `create question lazily creates coverage and stores selected concept angle`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        serviceStudies.rows += StudyEntity(
            id = 84,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Redis",
            difficultyLevel = 7,
            intervalMinutes = 15,
            customPrompt = "Ask production questions.",
            openaiModel = "gpt-5.4",
        )
        openAI.coverageBlueprint = listOf(
            OpenAIPort.QuestionCoverageConcept(
                key = "persistence",
                name = "Persistence",
                angles = emptyList(),
                children = listOf(
                    OpenAIPort.QuestionCoverageConcept(
                        key = "aof",
                        name = "AOF",
                        angles = emptyList(),
                        children = listOf(
                            OpenAIPort.QuestionCoverageConcept(
                                key = "recovery",
                                name = "Recovery",
                                angles = listOf(OpenAIPort.QuestionCoverageAngle("failure_mode", "Failure Mode")),
                            )
                        ),
                    )
                ),
            )
        )

        val response = service.createQuestion(principal, studyId = 84)

        assertThat(response.question.question).isEqualTo("Question")
        assertThat(openAI.coverageBlueprintCalls).isEqualTo(1)
        assertThat(questionCoverage.createdBlueprintStudyIds).containsExactly(84)
        assertThat(questions.visibleRows.single { it.id == response.id.toLong() }.conceptId).isEqualTo(1)
        assertThat(questions.visibleRows.single { it.id == response.id.toLong() }.angleKey).isEqualTo("failure_mode")
        assertThat(questionCoverage.markAskedCalls).containsExactly(
            QuestionCoverageSelection(
                conceptId = 1,
                coverageId = 1,
                conceptKey = "recovery",
                conceptName = "Recovery",
                angleKey = "failure_mode",
                angleName = "Failure Mode",
                conceptKeyPath = "persistence/aof/recovery",
                conceptPath = "Persistence > AOF > Recovery",
            )
        )
        assertThat(openAI.generatedPrompt?.userPrompt).contains("Focus concept path: Persistence > AOF > Recovery")
        assertThat(openAI.generatedPrompt?.userPrompt).contains("Focus concept: Recovery")
        assertThat(openAI.generatedPrompt?.userPrompt).contains("Question angle: Failure Mode")
    }

    @Test
    fun `graded answer updates coverage score when question has concept angle`(): Unit = runBlocking {
        users.row = UserEntity(id = principal.userId, providerId = "u7", status = "ACTIVE", appLanguage = "en")
        questions.visibleRows += pendingQuestion(id = 502, topic = "Redis").apply {
            conceptId = 11
            conceptKey = "replication"
            angleKey = "failure_mode"
        }

        service.answer(principal, recordId = 502, answer = "My answer", grade = true)

        assertThat(questionCoverage.markAnsweredCalls).containsExactly(
            FakeQuestionCoveragePort.AnsweredCall(conceptId = 11, angleKey = "failure_mode", score = 100, correct = true),
        )
    }

    @Test
    fun `create question uses system key and consumes monthly tier quota when user key is missing`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = "ACTIVE",
            appLanguage = "en",
        )
        memberships.usedCount = 2
        serviceStudies.rows += StudyEntity(
            id = 78,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Redis",
            difficultyLevel = 5,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )

        service.createQuestion(principal, studyId = 78)

        assertThat(openAI.generatedApiKeys).containsExactly("test-api-key")
        assertThat(memberships.usedCount).isEqualTo(3)
        assertThat(users.savedRows).isEmpty()
    }

    @Test
    fun `create question rejects after monthly tier question limit`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = "ACTIVE",
            appLanguage = "en",
        )
        memberships.activePlan = QuestionMembershipPlan(tierCode = "TIER1", monthlyQuestionLimit = 7)
        memberships.usedCount = 7
        serviceStudies.rows += StudyEntity(
            id = 79,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Postgres",
            difficultyLevel = 5,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )

        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { service.createQuestion(principal, studyId = 79) }
        }
            .isInstanceOf(com.buddystudy.backend.common.application.error.ApiException::class.java)
            .hasMessage("Monthly question limit reached.")
            .extracting("code")
            .isEqualTo(ApiErrorCode.QUOTA_EXCEEDED)

        assertThat(openAI.generateCalls).isZero()
    }

    @Test
    fun `create question uses system key and consumes monthly tier quota even when user key is configured`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = "ACTIVE",
            appLanguage = "en",
            openaiApiKeyCipher = cipher.encrypt("sk-user"),
        )
        memberships.usedCount = 29
        serviceStudies.rows += StudyEntity(
            id = 80,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Kafka",
            difficultyLevel = 5,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )

        service.createQuestion(principal, studyId = 80)

        assertThat(openAI.generatedApiKeys).containsExactly("test-api-key")
        assertThat(memberships.usedCount).isEqualTo(30)
        assertThat(memberships.consumeCalls).isEqualTo(1)
    }

    @Test
    fun `create question does not consume monthly tier quota when openai generation fails`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = "ACTIVE",
            appLanguage = "en",
        )
        memberships.usedCount = 2
        openAI.failure = IllegalStateException("OpenAI unavailable")
        serviceStudies.rows += StudyEntity(
            id = 81,
            deviceId = principal.deviceId,
            userId = principal.userId,
            topic = "Redis",
            difficultyLevel = 5,
            intervalMinutes = 15,
            openaiModel = "gpt-5.4",
        )

        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { service.createQuestion(principal, studyId = 81) }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(openAI.generatedApiKeys).containsExactly("test-api-key")
        assertThat(memberships.usedCount).isEqualTo(2)
        assertThat(memberships.consumeCalls).isEqualTo(1)
        assertThat(memberships.refundCalls).isEqualTo(1)
    }

    @Test
    fun `publicity reads question stats only for final response`(): Unit = runBlocking {
        questions.visibleRows += gradedQuestion(id = 401, topic = "Swift")
        questionStats.rows += QuestionStatsEntity(questionId = 401, likeCount = 2)

        val response = service.publicity(principal, id = 401, isPublic = true)

        assertThat(response.id).isEqualTo("401")
        assertThat(response.likeCount).isEqualTo(2)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
    }

    private fun gradedQuestion(id: Long, topic: String) = question(id, topic).apply {
        status = "graded"
        answer = "Answer"
        score = 90
        correct = true
        feedback = "Good"
        explanation = "Because"
        answeredAt = createdAt.plusSeconds(60)
        gradedAt = createdAt.plusSeconds(60)
    }

    private fun pendingQuestion(id: Long, topic: String) = question(id, topic).apply {
        status = "ungraded"
    }

    private fun question(id: Long, topic: String) = QuestionEntity(
        id = id,
        deviceId = "dev-1",
        userId = principal.userId,
        question = "Question $topic",
        topic = topic,
        difficultyLevel = 5,
        scheduledFor = Instant.parse("2026-06-10T00:00:00Z"),
        sentAt = Instant.parse("2026-06-10T00:00:00Z"),
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
    )

    private class FakeQuestionPort : QuestionPort {
        val visibleRows = mutableListOf<QuestionEntity>()
        val pendingRows = mutableListOf<QuestionEntity>()
        override suspend fun save(entity: QuestionEntity): QuestionEntity {
            if (entity.id == 0L) {
                entity.id = ((visibleRows + pendingRows).maxOfOrNull { it.id } ?: 0L) + 1
            }
            visibleRows += entity
            return entity
        }
        override suspend fun findQuestionById(id: Long): QuestionEntity? = null
        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? =
            (visibleRows + pendingRows).firstOrNull { it.id == id && it.userId == userId && it.deletedAt == null }
        override suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = PageImpl(pendingRows, pageable, pendingRows.size.toLong())
        override suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> = pendingRows.filter { it.studyId in studyIds }
        override suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = PageImpl(visibleRows, pageable, visibleRows.size.toLong())
        override suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = PageImpl(visibleRows.filter { it.topic.contains(query, ignoreCase = true) }, pageable, visibleRows.size.toLong())
        var findRecentQuestionTextsByStudyIdAndTopicCalls = 0
        var findRecentQuestionTextsByUserIdAndTopicCalls = 0
        override suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> {
            findRecentQuestionTextsByStudyIdAndTopicCalls += 1
            return visibleRows
                .filter { it.studyId == studyId && it.topic.equals(topic, ignoreCase = true) && it.deletedAt == null }
                .sortedByDescending { it.createdAt }
                .map { it.question }
                .take(pageable.pageSize)
        }
        override suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> {
            findRecentQuestionTextsByUserIdAndTopicCalls += 1
            return visibleRows
                .filter { it.userId == userId && it.topic.equals(topic, ignoreCase = true) && it.deletedAt == null }
                .sortedByDescending { it.createdAt }
                .map { it.question }
                .take(pageable.pageSize)
        }
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
            val row = (visibleRows + pendingRows).firstOrNull { it.id == id && it.userId == userId } ?: return 0
            row.deletedAt = now
            return 1
        }
        override suspend fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int {
            val rows = (visibleRows + pendingRows).filter { it.studyId == studyId && it.userId == userId && it.deletedAt == null }
            rows.forEach { it.deletedAt = now }
            return rows.size
        }
        override suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int {
            val rows = (visibleRows + pendingRows).filter {
                it.userId == userId && it.topic.equals(topic, ignoreCase = true) && it.deletedAt == null
            }
            rows.forEach { it.deletedAt = now }
            return rows.size
        }
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

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        override suspend fun save(entity: StudyEntity): StudyEntity = entity
        override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long = rows.removeAll { it.id == id && it.userId == userId }.let { if (it) 1 else 0 }
        override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
            rows.firstOrNull { it.id == id && it.userId == userId }
        override suspend fun findByUserIdAndParentStudyIdAndTopic(
            userId: Long,
            parentStudyId: Long?,
            topic: String,
        ): StudyEntity? = rows.firstOrNull {
            it.userId == userId && it.parentStudyId == parentStudyId && it.topic == topic
        }
        override suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = null
        override suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> = emptyList()
        override suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class FakeUserPort : UserPort {
        var row: UserEntity? = null
        val savedRows = mutableListOf<UserEntity>()
        var findByIdCalls = 0
        override suspend fun save(entity: UserEntity): UserEntity {
            savedRows += entity
            return entity
        }
        override suspend fun findById(id: Long): UserEntity? {
            findByIdCalls += 1
            return row?.takeIf { it.id == id }
        }
        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> =
            row?.takeIf { it.id in ids.toSet() }?.let { mutableListOf(it) } ?: mutableListOf()
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionMembershipPort : QuestionMembershipPort {
        var activePlan = QuestionMembershipPlan(tierCode = "TIER1", monthlyQuestionLimit = 30)
        var usedCount = 0
        var consumeCalls = 0
        var refundCalls = 0
        override suspend fun activePlanForUser(userId: Long): QuestionMembershipPlan? = activePlan
        override suspend fun quotaStatusForUser(userId: Long, yearMonth: YearMonth): QuestionQuotaStatus =
            QuestionQuotaStatus(
                tierCode = activePlan.tierCode,
                usedCount = usedCount,
                monthlyQuestionLimit = activePlan.monthlyQuestionLimit,
            )
        override suspend fun tryConsumeMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, limit: Int, now: Instant): Boolean {
            consumeCalls += 1
            if (usedCount >= limit) return false
            usedCount += 1
            return true
        }
        override suspend fun refundMonthlySystemQuestion(userId: Long, yearMonth: YearMonth, now: Instant) {
            refundCalls += 1
            if (usedCount > 0) usedCount -= 1
        }
    }

    private class FakeOpenAI : OpenAIPort {
        var gradeCalls = 0
        var generateCalls = 0
        var failure: RuntimeException? = null
        var generatedPrompt: QuestionGenerationPrompt? = null
        val generatedApiKeys = mutableListOf<String>()
        override suspend fun validate(apiKey: String) = Unit
        override suspend fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
            generateCalls += 1
            generatedApiKeys += apiKey
            failure?.let { throw it }
            generatedPrompt = prompt
            assertThat(prompt.userPrompt).contains("Language: English")
            if (generatedQuestions.isNotEmpty()) return generatedQuestions.removeFirst()
            return GeneratedQuestion("Question", null)
        }
        val generatedQuestions = ArrayDeque<GeneratedQuestion>()
        val embeddings = mutableMapOf<String, List<Float>>()
        override suspend fun embedText(apiKey: String, text: String): List<Float> =
            embeddings[text] ?: listOf(0f, 0f, 1f)
        var coverageBlueprintCalls = 0
        var coverageBlueprint = emptyList<OpenAIPort.QuestionCoverageConcept>()
        override suspend fun generateQuestionCoverageBlueprint(apiKey: String, model: String, topic: String, level: Int, customPrompt: String): List<OpenAIPort.QuestionCoverageConcept> {
            coverageBlueprintCalls += 1
            return coverageBlueprint
        }
        override suspend fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer {
            gradeCalls += 1
            assertThat(language).isEqualTo("en")
            return GradedAnswer(100, true, "Good", "Because")
        }
    }

    private class FakeQuestionEmbeddingPort : QuestionEmbeddingPort {
        val rows = mutableListOf<QuestionEmbeddingCandidate>()
        val savedRows = mutableListOf<QuestionEmbeddingCandidate>()
        val savedStudyIds = mutableListOf<Long>()
        override suspend fun save(questionId: Long, userId: Long, studyId: Long, topic: String, question: String, embedding: List<Float>): QuestionEmbeddingCandidate {
            val row = QuestionEmbeddingCandidate(questionId, question, embedding)
            savedRows += row
            savedStudyIds += studyId
            rows += row
            return row
        }

        override suspend fun findRecentByStudyIdAndTopic(studyId: Long, topic: String, limit: Int): List<QuestionEmbeddingCandidate> =
            rows.take(limit)
    }

    private class FakeQuestionCoveragePort : QuestionCoveragePort {
        val createdBlueprintStudyIds = mutableListOf<Long>()
        val markAskedCalls = mutableListOf<QuestionCoverageSelection>()
        val markAnsweredCalls = mutableListOf<AnsweredCall>()
        private var selection: QuestionCoverageSelection? = null
        override suspend fun ensureCoverage(
            studyId: Long,
            topic: String,
            concepts: List<QuestionCoveragePort.CoverageConceptBlueprint>,
        ) {
            createdBlueprintStudyIds += studyId
            val leaf = firstLeaf(concepts.first())
            selection = QuestionCoverageSelection(
                conceptId = 1,
                coverageId = 1,
                conceptKey = leaf.concept.key,
                conceptName = leaf.concept.name,
                angleKey = leaf.concept.angles.first().key,
                angleName = leaf.concept.angles.first().name,
                conceptKeyPath = leaf.keyPath,
                conceptPath = leaf.namePath,
            )
        }

        private fun firstLeaf(
            concept: QuestionCoveragePort.CoverageConceptBlueprint,
            parentKeyPath: String = "",
            parentNamePath: String = "",
        ): LeafSelection {
            val keyPath = listOf(parentKeyPath, concept.key).filter { it.isNotBlank() }.joinToString("/")
            val namePath = listOf(parentNamePath, concept.name).filter { it.isNotBlank() }.joinToString(" > ")
            if (concept.children.isEmpty()) return LeafSelection(concept, keyPath, namePath)
            return firstLeaf(concept.children.first(), keyPath, namePath)
        }

        private data class LeafSelection(
            val concept: QuestionCoveragePort.CoverageConceptBlueprint,
            val keyPath: String,
            val namePath: String,
        )

        override suspend fun selectNext(studyId: Long): QuestionCoverageSelection? = selection

        override suspend fun markAsked(selection: QuestionCoverageSelection, now: Instant) {
            markAskedCalls += selection
        }

        override suspend fun markAnswered(conceptId: Long, angleKey: String, score: Int, correct: Boolean, now: Instant) {
            markAnsweredCalls += AnsweredCall(conceptId, angleKey, score, correct)
        }

        data class AnsweredCall(val conceptId: Long, val angleKey: String, val score: Int, val correct: Boolean)
    }

    private class FakeNotificationOutbox : RedisEventOutboxAppendPort {
        val commands = mutableListOf<NotificationRequestCommand>()
        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long {
            commands += command
            return commands.size.toLong()
        }
    }

    private class FakeQuestionPushOutbox : QuestionPushOutboxAppendPort {
        val requests = mutableListOf<QuestionPushRequest>()

        override suspend fun enqueue(request: QuestionPushRequest, now: Instant): Long {
            requests += request
            return requests.size.toLong()
        }
    }

    private class NoOpOutboxPublisher : PublishOutboxUseCase {
        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary =
            OutboxPublishSummary(references.size, references.size, 0)
    }

}
