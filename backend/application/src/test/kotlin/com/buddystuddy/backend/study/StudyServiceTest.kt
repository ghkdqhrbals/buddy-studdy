package com.buddystuddy.backend.study

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystuddy.backend.community.application.port.outbound.SearchResult
import com.buddystuddy.backend.community.application.service.QuestionSearchSyncManager
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionMembershipPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionSearchTranslationPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.backend.study.application.port.outbound.TranslatedQuestionSearchText
import com.buddystuddy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystuddy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystuddy.backend.study.application.prompt.QuestionGenerationPrompt
import com.buddystuddy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystuddy.backend.study.application.service.QuestionCreationWriteManager
import com.buddystuddy.backend.study.application.service.StudyService
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity
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
    private val serviceStudies = FakeStudyPort()
    private val memberships = FakeQuestionMembershipPort()
    private val properties = BuddyStuddyProperties().apply { openai.apiKey = "test-api-key" }
    private val cipher = KeyCipher(BuddyStuddyProperties().apply { crypto.masterKey = "test-key" })
    private val service = StudyService(
        properties = properties,
        studies = serviceStudies,
        questions = questions,
        questionStats = questionStats,
        openAI = openAI,
        users = users,
        cipher = cipher,
        questionKeys = OpenAIQuestionKeyProvider(properties, cipher, memberships),
        questionPrompts = QuestionPromptProvider(),
        questionDiversity = QuestionDiversityPolicy(),
        questionWriter = QuestionCreationWriteManager(
            questions = questions,
            questionStats = questionStats,
            questionCreatedPublisher = FakeQuestionCreatedPublisher(),
            notifications = FakeNotificationPublisher(),
        ),
        questionSearch = QuestionSearchSyncManager(BuddyStuddyProperties(), questions, users, FakeQuestionSearchPort(), FakeQuestionSearchTranslator()),
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `records load question stats in one batch`() {
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
    fun `pending records load question stats in one batch`() {
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
    fun `skip reads question stats only for final response`() {
        questions.visibleRows += pendingQuestion(id = 301, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 301, viewCount = 5)

        val response = service.skip(principal, id = 301)

        assertThat(response.id).isEqualTo("301")
        assertThat(response.viewCount).isEqualTo(5)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
    }

    @Test
    fun `graded answer loads user and question stats only once`() {
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
    fun `delete soft deletes graded record`() {
        questions.visibleRows += gradedQuestion(id = 601, topic = "Redis")

        service.delete(principal, id = 601)

        assertThat(questions.visibleRows.single { it.id == 601L }.deletedAt).isNotNull()
    }

    @Test
    fun `create question reuses loaded user for search sync`() {
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
    }

    @Test
    fun `create question sends same study and same topic history before openai generation`() {
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
    fun `create question uses system key and consumes monthly tier quota when user key is missing`() {
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
    fun `create question requires user key after monthly tier question limit`() {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = "ACTIVE",
            appLanguage = "en",
        )
        memberships.usedCount = 30
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
            service.createQuestion(principal, studyId = 79)
        }
            .isInstanceOf(com.buddystuddy.backend.common.application.error.ApiException::class.java)
            .hasMessage("Monthly question limit reached. Add your OpenAI API key to continue.")

        assertThat(openAI.generateCalls).isZero()
    }

    @Test
    fun `create question uses user key without consuming monthly tier quota`() {
        users.row = UserEntity(
            id = principal.userId,
            providerId = "u7",
            status = "ACTIVE",
            appLanguage = "en",
            openaiApiKeyCipher = cipher.encrypt("sk-user"),
        )
        memberships.usedCount = 30
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

        assertThat(openAI.generatedApiKeys).containsExactly("sk-user")
        assertThat(memberships.consumeCalls).isZero()
    }

    @Test
    fun `create question does not consume monthly tier quota when openai generation fails`() {
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
            service.createQuestion(principal, studyId = 81)
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(openAI.generatedApiKeys).containsExactly("test-api-key")
        assertThat(memberships.usedCount).isEqualTo(2)
        assertThat(memberships.consumeCalls).isEqualTo(1)
        assertThat(memberships.refundCalls).isEqualTo(1)
    }

    @Test
    fun `publicity reads question stats only for final response`() {
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
        override fun save(entity: QuestionEntity): QuestionEntity {
            if (entity.id == 0L) {
                entity.id = ((visibleRows + pendingRows).maxOfOrNull { it.id } ?: 0L) + 1
            }
            visibleRows += entity
            return entity
        }
        override fun findQuestionById(id: Long): Optional<QuestionEntity> = Optional.empty()
        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? =
            (visibleRows + pendingRows).firstOrNull { it.id == id && it.userId == userId && it.deletedAt == null }
        override fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = PageImpl(pendingRows, pageable, pendingRows.size.toLong())
        override fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> = pendingRows.filter { it.studyId in studyIds }
        override fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = PageImpl(visibleRows, pageable, visibleRows.size.toLong())
        override fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = PageImpl(visibleRows.filter { it.topic.contains(query, ignoreCase = true) }, pageable, visibleRows.size.toLong())
        var findRecentQuestionTextsByStudyIdAndTopicCalls = 0
        var findRecentQuestionTextsByUserIdAndTopicCalls = 0
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
        override fun countPendingForStudy(studyId: Long): Long = pendingRows.count { it.studyId == studyId }.toLong()
        override fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> =
            pendingRows
                .filter { it.studyId in studyIds }
                .groupingBy { it.studyId!! }
                .eachCount()
                .mapValues { it.value.toLong() }
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun softDelete(id: Long, userId: Long, now: Instant): Int {
            val row = (visibleRows + pendingRows).firstOrNull { it.id == id && it.userId == userId } ?: return 0
            row.deletedAt = now
            return 1
        }
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        override fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override fun findById(id: Long): Optional<QuestionStatsEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(rows.firstOrNull { it.questionId == id })
        }
        override fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity> {
            findAllByIdsCalls += 1
            return rows.filter { it.questionId in ids }
        }
        override fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementLike(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        override fun save(entity: StudyEntity): StudyEntity = entity
        override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
            rows.firstOrNull { it.id == id && it.userId == userId }
        override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = null
        override fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> = emptyList()
        override fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class FakeUserPort : UserPort {
        var row: UserEntity? = null
        val savedRows = mutableListOf<UserEntity>()
        var findByIdCalls = 0
        override fun save(entity: UserEntity): UserEntity {
            savedRows += entity
            return entity
        }
        override fun findById(id: Long): Optional<UserEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(row?.takeIf { it.id == id })
        }
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> =
            row?.takeIf { it.id in ids.toSet() }?.let { mutableListOf(it) } ?: mutableListOf()
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

    private class FakeOpenAI : OpenAIPort {
        var gradeCalls = 0
        var generateCalls = 0
        var failure: RuntimeException? = null
        var generatedPrompt: QuestionGenerationPrompt? = null
        val generatedApiKeys = mutableListOf<String>()
        override fun validate(apiKey: String) = Unit
        override fun generateQuestion(apiKey: String, model: String, prompt: QuestionGenerationPrompt): GeneratedQuestion {
            generateCalls += 1
            generatedApiKeys += apiKey
            failure?.let { throw it }
            generatedPrompt = prompt
            assertThat(prompt.userPrompt).contains("Language: English")
            return GeneratedQuestion("Question", null)
        }
        override fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String): GradedAnswer {
            gradeCalls += 1
            assertThat(language).isEqualTo("en")
            return GradedAnswer(100, true, "Good", "Because")
        }
    }

    private class FakeNotificationPublisher : PublishNotificationUseCase {
        val commands = mutableListOf<NotificationRequestCommand>()
        override fun publish(command: NotificationRequestCommand): Boolean {
            commands += command
            return true
        }
    }

    private class FakeQuestionCreatedPublisher : QuestionCreatedPublishPort {
        override fun publishQuestionCreated(questionId: Long, language: String, createdAt: Instant): Boolean = true
    }

    private class FakeQuestionSearchTranslator : QuestionSearchTranslationPort {
        override fun translateSearchText(
            sourceLanguage: String,
            targetLanguage: String,
            topic: String,
            question: String,
            answer: String?,
            feedback: String?,
            explanation: String?,
        ): TranslatedQuestionSearchText =
            TranslatedQuestionSearchText(topic, question, answer, feedback, explanation)
    }

    private class FakeQuestionSearchPort : QuestionSearchPort {
        override fun save(entity: QuestionSearchEntity): QuestionSearchEntity = entity
        override fun deleteByQuestionId(questionId: Long): Long = 0
        override fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult = SearchResult(emptyList(), 0)
        override fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? = null
        override fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? = null
    }

}
