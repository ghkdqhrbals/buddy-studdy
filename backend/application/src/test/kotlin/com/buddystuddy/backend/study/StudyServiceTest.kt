package com.buddystuddy.backend.study

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystuddy.backend.community.application.port.outbound.SearchResult
import com.buddystuddy.backend.community.application.service.QuestionSearchSyncManager
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.study.application.port.outbound.GeneratedQuestion
import com.buddystuddy.backend.study.application.port.outbound.GradedAnswer
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
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
import java.util.Optional

class StudyServiceTest {
    private val questions = FakeQuestionPort()
    private val questionStats = FakeQuestionStatsPort()
    private val service = StudyService(
        properties = BuddyStuddyProperties(),
        studies = FakeStudyPort(),
        questions = questions,
        questionStats = questionStats,
        openAI = FakeOpenAI(),
        users = FakeUserPort(),
        cipher = KeyCipher(BuddyStuddyProperties().apply { crypto.masterKey = "test-key" }),
        questionWriter = QuestionCreationWriteManager(
            questions = questions,
            questionStats = questionStats,
            pushOutbox = FakePushOutboxPort(),
            questionSearch = QuestionSearchSyncManager(questions, FakeUserPort(), FakeQuestionSearchPort()),
        ),
        questionSearch = QuestionSearchSyncManager(questions, FakeUserPort(), FakeQuestionSearchPort()),
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
        override fun save(entity: QuestionEntity): QuestionEntity = entity
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
        override fun countPendingForStudy(studyId: Long): Long = pendingRows.count { it.studyId == studyId }.toLong()
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
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
        override fun save(entity: StudyEntity): StudyEntity = entity
        override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = null
        override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? = null
        override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? = null
        override fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> = emptyList()
        override fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> = Page.empty()
        override fun findDue(now: Instant, pageable: Pageable): List<StudyEntity> = emptyList()
    }

    private class FakeUserPort : UserPort {
        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> = Optional.empty()
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = mutableListOf()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeOpenAI : OpenAIPort {
        override fun validate(apiKey: String) = Unit
        override fun generateQuestion(apiKey: String, model: String, topic: String, level: Int, language: String, customPrompt: String, recent: List<String>) =
            GeneratedQuestion("Question", null)
        override fun grade(apiKey: String, model: String, question: String, answer: String, topic: String, level: Int, language: String) =
            GradedAnswer(100, true, "Good", "Because")
    }

    private class FakePushOutboxPort : QuestionPushOutboxPort {
        override fun enqueue(request: QuestionPushRequest, now: Instant) = Unit
    }

    private class FakeQuestionSearchPort : QuestionSearchPort {
        override fun save(entity: QuestionSearchEntity): QuestionSearchEntity = entity
        override fun deleteByQuestionId(questionId: Long): Long = 0
        override fun searchPublic(query: String?, limit: Int, offset: Int): SearchResult = SearchResult(emptyList(), 0)
    }
}
