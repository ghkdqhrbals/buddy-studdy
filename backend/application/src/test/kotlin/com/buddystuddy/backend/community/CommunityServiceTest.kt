package com.buddystuddy.backend.community

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystuddy.backend.community.application.port.outbound.ReportPort
import com.buddystuddy.backend.community.application.port.outbound.SearchResult
import com.buddystuddy.backend.community.application.service.CommunityService
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystuddy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystuddy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.community.domain.entity.QuestionCommentEntity
import com.buddystuddy.community.domain.entity.QuestionLikeEntity
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.community.domain.entity.ReportEntity
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

class CommunityServiceTest {
    private val users = FakeUserPort()
    private val questions = FakeQuestionPort()
    private val questionStats = FakeQuestionStatsPort()
    private val likes = FakeQuestionLikePort()
    private val search = FakeQuestionSearchPort()
    private val notificationPublisher = FakeNotificationPublisher()
    private val service = CommunityService(
        users = users,
        questions = questions,
        questionStats = questionStats,
        likes = likes,
        comments = FakeQuestionCommentPort(),
        reports = FakeReportPort(),
        reactions = FakeReactionPublisher(),
        search = search,
        notifications = notificationPublisher,
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `public question list loads authors stats and liked flags in batches`() {
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        users.rows += UserEntity(id = 11, providerId = "u11", displayName = "Author B")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questions.rows += publicQuestion(id = 101, userId = 11, topic = "SwiftUI")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 2, commentCount = 1, viewCount = 20)
        questionStats.rows += QuestionStatsEntity(questionId = 101, likeCount = 3, commentCount = 2, viewCount = 30)
        likes.rows += QuestionLikeEntity(questionId = 101, userId = 7)

        val response = service.getPublicQuestions(principal, query = null, language = "ko", limit = 20, offset = 0)

        assertThat(response.questions.map { it.author?.displayName }).containsExactly("Author B", "Author A")
        assertThat(response.questions.map { it.viewCount }).containsExactly(30, 20)
        assertThat(response.questions.map { it.isLikedByMe }).containsExactly(true, false)
        assertThat(users.findByIdCalls).isZero()
        assertThat(users.findAllByIdCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
        assertThat(likes.existsCalls).isZero()
        assertThat(likes.findLikedQuestionIdsCalls).isEqualTo(1)
    }

    @Test
    fun `public question v2 overlays translated search text in response`() {
        users.rows += UserEntity(id = 7, providerId = "viewer", displayName = "Viewer", appLanguage = "en")
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "원본 주제")
        search.rows += QuestionSearchEntity(
            questionId = 100,
            language = "en",
            userId = 10,
            topic = "Translated topic",
            question = "Translated question",
            answer = "Translated answer",
            feedback = "Translated feedback",
            explanation = "Translated explanation",
            authorDisplayName = "Author",
            publicQuestion = true,
            score = 90,
            answeredAt = Instant.parse("2026-06-10T01:00:00Z"),
            createdAt = Instant.parse("2026-06-10T00:00:00Z"),
        )

        val response = service.getPublicQuestionsV2(principal, query = null, language = "en", limit = 20, offset = 0)

        val question = response.questions.single()
        assertThat(question.topic).isEqualTo("Translated topic")
        assertThat(question.question).isEqualTo("Translated question")
        assertThat(question.answer).isEqualTo("Translated answer")
        assertThat(question.gradingResult?.feedback).isEqualTo("Translated feedback")
        assertThat(question.gradingResult?.explanation).isEqualTo("Translated explanation")
    }

    @Test
    fun `liking a public question increments stats without recounting likes`() {
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 3, commentCount = 1, viewCount = 20)

        val response = service.setLike(principal, id = 100, liked = true)

        assertThat(response.likeCount).isEqualTo(4)
        assertThat(response.isLikedByMe).isTrue()
        assertThat(questionStats.incrementLikeCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
        val notification = notificationPublisher.rows.single()
        assertThat(notification.eventId).isEqualTo("question-like-100-7")
        assertThat(notification.userId).isEqualTo(10)
        assertThat(notification.actorUserId).isEqualTo(7)
        assertThat(notification.threadType).isEqualTo("question")
        assertThat(notification.threadId).isEqualTo("100")
        assertThat(notification.shouldPush).isFalse()
    }

    @Test
    fun `commenting on another user's question publishes push eligible thread notification`() {
        users.rows += UserEntity(id = 7, providerId = "u7", displayName = "Commenter")
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")

        val response = service.createComment(principal, id = 100, body = "좋은 질문입니다.")

        assertThat(response.body).isEqualTo("좋은 질문입니다.")
        val notification = notificationPublisher.rows.single()
        assertThat(notification.eventId).isEqualTo("question-comment-1")
        assertThat(notification.userId).isEqualTo(10)
        assertThat(notification.actorUserId).isEqualTo(7)
        assertThat(notification.threadType).isEqualTo("question")
        assertThat(notification.threadId).isEqualTo("100")
        assertThat(notification.deepLink).isEqualTo("buddystuddy://public/questions/100")
        assertThat(notification.shouldPush).isTrue()
    }

    @Test
    fun `empty comment page skips author lookup`() {
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")

        val response = service.getComments(id = 100, limit = 20, offset = 0)

        assertThat(response.comments).isEmpty()
        assertThat(response.totalCount).isZero()
        assertThat(users.findAllByIdCalls).isZero()
    }

    private fun publicQuestion(id: Long, userId: Long, topic: String) = QuestionEntity(
        id = id,
        deviceId = "dev-1",
        userId = userId,
        question = "Question $topic",
        topic = topic,
        difficultyLevel = 5,
        scheduledFor = Instant.parse("2026-06-10T00:00:00Z"),
        status = "graded",
        answer = "Answer",
        score = 90,
        correct = true,
        feedback = "Good",
        explanation = "Because",
        answeredAt = Instant.parse("2026-06-10T01:00:00Z"),
        gradedAt = Instant.parse("2026-06-10T01:00:00Z"),
        publicQuestion = true,
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T01:00:00Z"),
    )

    private class FakeUserPort : UserPort {
        val rows = mutableListOf<UserEntity>()
        var findByIdCalls = 0
        var findAllByIdCalls = 0

        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(rows.firstOrNull { it.id == id })
        }

        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> {
            findAllByIdCalls += 1
            val idSet = ids.toSet()
            return rows.filter { it.id in idSet }.toMutableList()
        }

        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionPort : QuestionPort {
        val rows = mutableListOf<QuestionEntity>()
        override fun save(entity: QuestionEntity): QuestionEntity = entity
        override fun findQuestionById(id: Long): Optional<QuestionEntity> = Optional.empty()
        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun countPendingForStudy(studyId: Long): Long = 0
        override fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> = emptyMap()
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = PageImpl(rows.sortedByDescending { it.createdAt })
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = rows.firstOrNull { it.id == id }
        override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = rows.filter { it.id in ids }
        override fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        var incrementLikeCalls = 0

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
        override fun incrementLike(questionId: Long, delta: Int, now: Instant): Int {
            incrementLikeCalls += 1
            val row = rows.firstOrNull { it.questionId == questionId } ?: return 0
            row.likeCount = maxOf(0, row.likeCount + delta)
            row.updatedAt = now
            return 1
        }
        override fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeQuestionLikePort : QuestionLikePort {
        val rows = mutableListOf<QuestionLikeEntity>()
        var existsCalls = 0
        var findLikedQuestionIdsCalls = 0

        override fun save(entity: QuestionLikeEntity): QuestionLikeEntity {
            rows += entity
            return entity
        }
        override fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean {
            existsCalls += 1
            return rows.any { it.questionId == questionId && it.userId == userId }
        }

        override fun findLikedQuestionIds(userId: Long, questionIds: Collection<Long>): Set<Long> {
            findLikedQuestionIdsCalls += 1
            return rows.filter { it.userId == userId && it.questionId in questionIds }.map { it.questionId }.toSet()
        }

        override fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long = 0
    }

    private class FakeQuestionCommentPort : QuestionCommentPort {
        private var nextId = 1L
        override fun save(entity: QuestionCommentEntity): QuestionCommentEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            return entity
        }
        override fun findByIdAndQuestionIdAndDeletedAtIsNull(id: Long, questionId: Long): QuestionCommentEntity? = null
        override fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(questionId: Long, pageable: Pageable): Page<QuestionCommentEntity> = Page.empty()
    }

    private class FakeReportPort : ReportPort {
        override fun save(entity: ReportEntity): ReportEntity = entity
    }

    private class FakeReactionPublisher : PublicQuestionReactionPublishPort {
        override fun publishViewed(questionId: Long, userId: Long?): Boolean = true
    }

    private class FakeQuestionSearchPort : QuestionSearchPort {
        val rows = mutableListOf<QuestionSearchEntity>()
        override fun save(entity: QuestionSearchEntity): QuestionSearchEntity {
            rows += entity
            return entity
        }
        override fun deleteByQuestionId(questionId: Long): Long = 0
        override fun searchPublic(query: String?, language: String, limit: Int, offset: Int): SearchResult =
            SearchResult(rows.filter { it.language == language }.map { it.questionId }, rows.count { it.language == language }.toLong())

        override fun findPublicByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? =
            rows.firstOrNull { it.questionId == questionId && it.language == language }

        override fun findByQuestionIdAndLanguage(questionId: Long, language: String): QuestionSearchEntity? =
            rows.firstOrNull { it.questionId == questionId && it.language == language }
    }

    private class FakeNotificationPublisher : PublishNotificationUseCase {
        val rows = mutableListOf<NotificationRequestCommand>()
        override fun publish(command: NotificationRequestCommand): Boolean {
            rows += command
            return true
        }
    }
}
