package com.buddystudy.backend.study

import kotlinx.coroutines.runBlocking

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.common.domain.SupportedLanguage
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
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.backend.study.application.service.QuestionCreationWriteService
import com.buddystudy.backend.study.application.service.StudyRecordWriteService
import com.buddystudy.backend.study.application.service.StudyService
import com.buddystudy.backend.study.application.model.AnswerGradingProgress
import com.buddystudy.backend.study.application.model.AnswerGradingRequestedEvent
import com.buddystudy.backend.study.application.model.AnswerGradingStatus
import com.buddystudy.backend.study.application.model.QuestionGeneratedEvent
import com.buddystudy.backend.study.application.port.outbound.AnswerGradingProgressPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.backend.test.PassthroughLanguageDetector
import com.buddystudy.backend.test.RecordingLocalizationRequests
import com.buddystudy.backend.test.RecordingContentTranslationEventPort
import com.buddystudy.backend.localization.application.service.ContentTranslationRequestManager
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
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
    private val properties = BuddyStudyProperties().apply { openai.userContentApiKey = "test-api-key" }
    private val cipher = KeyCipher(BuddyStudyProperties().apply { crypto.masterKey = "test-key" })
    private val questionKeys = OpenAIQuestionKeyProvider(UserContentOpenAIKeyProvider(properties), memberships)
    private val gradingProgress = FakeAnswerGradingProgressPort()
    private val notificationOutbox = FakeNotificationOutbox()
    private val translationEvents = RecordingContentTranslationEventPort()
    private val outboxPublisher = NoOpOutboxPublisher()
    private val recordWriter = StudyRecordWriteService(
        questions,
        questionCoverage,
        gradingProgress,
        notificationOutbox,
        PassthroughLanguageDetector(),
        ContentTranslationRequestManager(EmptyContentLocalizationPort(), translationEvents),
    )
    private val service = StudyService(
        questions = questions,
        questionStats = questionStats,
        recordWriter = recordWriter,
        gradingWriter = recordWriter,
        outboxPublisher = outboxPublisher,
        users = users,
        languageDetector = PassthroughLanguageDetector(),
        contentLocalizations = EmptyContentLocalizationPort(),
        localizationRequests = RecordingLocalizationRequests(),
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
    fun `records can be paged for one study tree node`(): Unit = runBlocking {
        questions.visibleRows += gradedQuestion(id = 101, topic = "Swift").apply { studyId = 11 }
        questions.visibleRows += gradedQuestion(id = 102, topic = "Kotlin").apply { studyId = 12 }
        questions.visibleRows += gradedQuestion(id = 103, topic = "Swift concurrency").apply { studyId = 11 }

        val response = service.records(
            principal,
            limit = 1,
            offset = 0,
            query = null,
            studyId = 11,
        )

        assertThat(response.records.map { it.studyId }).containsExactly(11)
        assertThat(response.totalCount).isEqualTo(2)
        assertThat(response.limit).isEqualTo(1)
        assertThat(response.offset).isZero()
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
    fun `graded answer is queued without waiting for OpenAI`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = UserStatus.ACTIVE,
            appLanguage = SupportedLanguage.ENGLISH,
        )
        questions.visibleRows += pendingQuestion(id = 501, topic = "Kotlin")
        questionStats.rows += QuestionStatsEntity(questionId = 501, viewCount = 5)

        val response = service.answer(principal, recordId = 501, answer = "My answer", grade = true)

        assertThat(response.id).isEqualTo("501")
        assertThat(response.answer).isEqualTo("My answer")
        assertThat(response.answeredAt).isNotNull()
        assertThat(response.gradingResult).isNull()
        assertThat(response.gradingStatus).isEqualTo(AnswerGradingStatus.QUEUED)
        assertThat(response.questionStatus).isEqualTo(QuestionStatus.GRADING)
        assertThat(response.gradingRequestId).isNotBlank()
        assertThat(response.correlationId).isEqualTo(response.gradingRequestId)
        assertThat(response.gradingLastEventId).isEqualTo(1)
        assertThat(notificationOutbox.gradingEvents).hasSize(1)
        assertThat(openAI.gradeCalls).isZero()
        assertThat(users.findByIdCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
        assertThat(
            translationEvents.events
                .filter { it.contentType == LocalizableContentType.ANSWER }
                .map { it.targetLanguage },
        ).containsExactlyInAnyOrder("ko", "ja")
        assertThat(outboxPublisher.published).isNotEmpty
    }

    @Test
    fun `answer without grading still appends locale translation work`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = UserStatus.ACTIVE,
            appLanguage = SupportedLanguage.ENGLISH,
        )
        questions.visibleRows += pendingQuestion(id = 504, topic = "Kotlin")

        val response = service.answer(principal, recordId = 504, answer = "My answer", grade = false)

        assertThat(response.answer).isEqualTo("My answer")
        assertThat(
            translationEvents.events
                .filter { it.contentType == LocalizableContentType.ANSWER }
                .map { it.targetLanguage },
        ).containsExactlyInAnyOrder("ko", "ja")
        assertThat(outboxPublisher.published).isNotEmpty
    }

    @Test
    fun `second answer submission is rejected without another grading event`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = UserStatus.ACTIVE,
            appLanguage = SupportedLanguage.ENGLISH,
        )
        questions.visibleRows += pendingQuestion(id = 503, topic = "Kotlin")

        service.answer(principal, recordId = 503, answer = "First answer", grade = true)
        val failure = runCatching {
            service.answer(principal, recordId = 503, answer = "Changed answer", grade = true)
        }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(com.buddystudy.backend.common.application.error.ApiException::class.java)
        assertThat(
            (failure as com.buddystudy.backend.common.application.error.ApiException).code,
        ).isEqualTo(ApiErrorCode.ANSWER_ALREADY_SUBMITTED)
        val persisted = questions.findByIdAndUserIdAndDeletedAtIsNull(503, principal.userId)
        assertThat(persisted?.answer).isEqualTo("First answer")
        assertThat(persisted?.status).isEqualTo(QuestionStatus.GRADING)
        assertThat(persisted?.gradingStatus).isEqualTo(AnswerGradingStatus.QUEUED)
        assertThat(persisted?.gradingLastEventId).isEqualTo(1)
        assertThat(notificationOutbox.gradingEvents).hasSize(1)
    }

    @Test
    fun `terminal grading failure moves the question to failed status`(): Unit = runBlocking {
        val now = Instant.parse("2026-08-03T01:00:00Z")
        val question = pendingQuestion(id = 505, topic = "Kotlin").apply {
            status = QuestionStatus.GRADING
            gradingRequestId = "request-505"
            gradingStatus = AnswerGradingStatus.JUDGING
        }
        questions.visibleRows += question
        val event = AnswerGradingRequestedEvent(
            eventId = "event-505",
            requestId = "request-505",
            recordId = 505,
            userId = principal.userId,
            requestedAt = now.minusSeconds(10),
            responseLanguage = "en",
        )

        recordWriter.fail(event, "Provider failed.", now)

        assertThat(question.status).isEqualTo(QuestionStatus.FAILED)
        assertThat(question.gradingStatus).isEqualTo(AnswerGradingStatus.FAILED)
        assertThat(question.gradingError).isEqualTo("Provider failed.")
    }

    @Test
    fun `delete soft deletes graded record`(): Unit = runBlocking {
        questions.visibleRows += gradedQuestion(id = 601, topic = "Redis")

        service.delete(principal, id = 601)

        assertThat(questions.visibleRows.single { it.id == 601L }.deletedAt).isNotNull()
    }

    @Test
    fun `clear soft deletes every record owned by the user`(): Unit = runBlocking {
        questions.visibleRows += gradedQuestion(id = 601, topic = "Redis")
        questions.visibleRows += gradedQuestion(id = 602, topic = "Kotlin").apply { userId = 99 }
        questions.pendingRows += pendingQuestion(id = 603, topic = "Swift")

        service.clear(principal)

        assertThat(questions.visibleRows.single { it.id == 601L }.deletedAt).isNotNull()
        assertThat(questions.pendingRows.single { it.id == 603L }.deletedAt).isNotNull()
        assertThat(questions.visibleRows.single { it.id == 602L }.deletedAt).isNull()
    }

    @Test
    fun `queued grading does not update coverage before the consumer completes`(): Unit = runBlocking {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = UserStatus.ACTIVE,
            appLanguage = SupportedLanguage.ENGLISH,
        )
        questions.visibleRows += pendingQuestion(id = 502, topic = "Redis").apply {
            conceptId = 11
            conceptKey = "replication"
            angleKey = "failure_mode"
        }

        service.answer(principal, recordId = 502, answer = "My answer", grade = true)

        assertThat(questionCoverage.markAnsweredCalls).isEmpty()
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

    @Test
    fun `record detail returns translated answer to its author`(): Unit = runBlocking {
        val question = gradedQuestion(id = 402, topic = "Redis").apply {
            sourceLanguage = SupportedLanguage.KOREAN
            answer = "Use AOF for stronger durability."
            answerSourceLanguage = SupportedLanguage.ENGLISH
        }
        questions.visibleRows += question
        val answerHash = requireNotNull(ContentSourceHashPolicy.recordHashes(question).answer)
        val localizedService = StudyService(
            questions = questions,
            questionStats = questionStats,
            recordWriter = recordWriter,
            gradingWriter = recordWriter,
            outboxPublisher = outboxPublisher,
            users = users,
            languageDetector = PassthroughLanguageDetector(),
            contentLocalizations = object : EmptyContentLocalizationPort() {
                override suspend fun record(questionId: Long, targetLanguage: String) =
                    RecordLocalizationSnapshot(
                        question = null,
                        answer = TextLocalizationSnapshot(
                            sourceLanguage = "en",
                            targetLanguage = "ko",
                            sourceHash = answerHash,
                            status = "READY",
                            fields = mapOf("answer" to "더 강한 내구성을 위해 AOF를 사용합니다."),
                            provider = "test",
                        ),
                        aiResponse = null,
                    )
            },
            localizationRequests = RecordingLocalizationRequests(),
        )

        val response = localizedService.record(
            principal = principal,
            id = question.id,
            language = "ko",
            view = "localized",
        )

        assertThat(response.answer).isEqualTo("더 강한 내구성을 위해 AOF를 사용합니다.")
        assertThat(response.localization?.answer?.isTranslated).isTrue()
        assertThat(response.localization?.answer?.displayLanguage).isEqualTo("ko")
    }

    private fun gradedQuestion(id: Long, topic: String) = question(id, topic).apply {
        status = QuestionStatus.GRADED
        answer = "Answer"
        score = 90
        correct = true
        feedback = "Good"
        explanation = "Because"
        answeredAt = createdAt.plusSeconds(60)
        gradedAt = createdAt.plusSeconds(60)
    }

    private fun pendingQuestion(id: Long, topic: String) = question(id, topic).apply {
        status = QuestionStatus.UNGRADED
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
        override suspend fun findVisibleByUserAndStudyId(
            userId: Long,
            includePending: Boolean,
            studyId: Long,
            query: String?,
            pageable: Pageable,
        ): Page<QuestionEntity> {
            val matches = visibleRows.filter {
                it.userId == userId &&
                    it.studyId == studyId &&
                    (includePending || it.score != null) &&
                    (query.isNullOrBlank() || it.topic.contains(query, ignoreCase = true))
            }
            val fromIndex = pageable.offset.toInt().coerceAtMost(matches.size)
            val toIndex = (fromIndex + pageable.pageSize).coerceAtMost(matches.size)
            return PageImpl(matches.subList(fromIndex, toIndex), pageable, matches.size.toLong())
        }
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
        override suspend fun softDeleteByUserId(userId: Long, now: Instant): Int {
            val rows = (visibleRows + pendingRows).filter { it.userId == userId && it.deletedAt == null }
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
        override suspend fun quotaStatusForUser(userId: Long, at: Instant): QuestionQuotaStatus =
            QuestionQuotaStatus(
                tierCode = activePlan.tierCode,
                usedCount = usedCount,
                monthlyQuestionLimit = activePlan.monthlyQuestionLimit,
            )
        override suspend fun tryConsumeMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, limit: Int, now: Instant): Boolean {
            consumeCalls += 1
            if (usedCount >= limit) return false
            usedCount += 1
            return true
        }
        override suspend fun refundMonthlySystemQuestion(userId: Long, periodStartedAt: Instant, now: Instant) {
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
            return GeneratedQuestion("Generated question", null)
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
        val gradingEvents = mutableListOf<AnswerGradingRequestedEvent>()
        val generatedEvents = mutableListOf<QuestionGeneratedEvent>()

        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long {
            commands += command
            return commands.size.toLong()
        }

        override suspend fun appendAnswerGrading(event: AnswerGradingRequestedEvent, createdAt: Instant): Long {
            gradingEvents += event
            return (commands.size + gradingEvents.size).toLong()
        }

        override suspend fun appendQuestionGenerated(event: QuestionGeneratedEvent, createdAt: Instant): Long {
            generatedEvents += event
            return (commands.size + gradingEvents.size + generatedEvents.size).toLong()
        }
    }

    private class FakeAnswerGradingProgressPort : AnswerGradingProgressPort {
        private val events = mutableListOf<AnswerGradingProgress>()

        override suspend fun append(
            recordId: Long,
            userId: Long,
            requestId: String,
            status: AnswerGradingStatus,
            questionStatus: QuestionStatus,
            errorMessage: String?,
            occurredAt: Instant,
        ): AnswerGradingProgress = AnswerGradingProgress(
            id = (events.size + 1).toLong(),
            recordId = recordId,
            requestId = requestId,
            status = status,
            questionStatus = questionStatus,
            errorMessage = errorMessage,
            occurredAt = occurredAt,
        ).also(events::add)

        override suspend fun findAfter(
            recordId: Long,
            userId: Long,
            requestId: String,
            afterId: Long,
            limit: Int,
        ): List<AnswerGradingProgress> = events
            .filter { it.recordId == recordId && it.requestId == requestId && it.id > afterId }
            .take(limit)
    }

    private class NoOpOutboxPublisher : PublishOutboxUseCase {
        val published = mutableListOf<OutboxReference>()

        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
            published += references
            return OutboxPublishSummary(references.size, references.size, 0)
        }
    }

}
