package com.buddystudy.backend.community

import kotlinx.coroutines.runBlocking

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.community.application.port.outbound.FeedbackPort
import com.buddystudy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystudy.backend.community.application.port.outbound.ReportPort
import com.buddystudy.backend.community.application.port.outbound.UserBlockPort
import com.buddystudy.backend.community.application.service.CommunityService
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.community.domain.entity.UserBlockEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.backend.test.PassthroughLanguageDetector
import com.buddystudy.backend.test.RecordingLocalizationRequests
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
    private val comments = FakeQuestionCommentPort()
    private val userBlocks = FakeUserBlockPort()
    private val notificationPublisher = FakeNotificationPublisher()
    private val reactionPublisher = FakeReactionPublisher()
    private val service = CommunityService(
        users = users,
        questions = questions,
        questionStats = questionStats,
        likes = likes,
        comments = comments,
        reports = FakeReportPort(),
        userBlocks = userBlocks,
        feedbacks = FakeFeedbackPort(),
        reactions = reactionPublisher,
        notifications = notificationPublisher,
        languageDetector = PassthroughLanguageDetector(),
        contentLocalizations = EmptyContentLocalizationPort(),
        localizationRequests = RecordingLocalizationRequests(),
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `public question list loads authors stats and liked flags in batches`(): Unit = runBlocking {
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
    fun `public question v2 returns canonical question text`(): Unit = runBlocking {
        users.rows += UserEntity(id = 7, providerId = "viewer", displayName = "Viewer", appLanguage = "en")
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "원본 주제")
        val response = service.getPublicQuestionsV2(principal, query = null, language = "en", limit = 20, offset = 0)

        val question = response.questions.single()
        assertThat(question.topic).isEqualTo("원본 주제")
        assertThat(question.question).isEqualTo("Question 원본 주제")
        assertThat(question.answer).isEqualTo("Answer")
        assertThat(question.gradingResult?.feedback).isEqualTo("Good")
        assertThat(question.gradingResult?.explanation).isEqualTo("Because")
    }

    @Test
    fun `blocked authors are hidden from public questions and comments`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "blocked", displayName = "Blocked")
        users.rows += UserEntity(id = 11, providerId = "visible", displayName = "Visible")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Blocked topic")
        questions.rows += publicQuestion(id = 101, userId = 11, topic = "Visible topic")
        comments.rows += QuestionCommentEntity(id = 1, questionId = 101, userId = 10, body = "hidden")
        comments.rows += QuestionCommentEntity(id = 2, questionId = 101, userId = 11, body = "visible")
        userBlocks.rows += UserBlockEntity(blockerUserId = principal.userId, blockedUserId = 10)

        val questionsResponse = service.getPublicQuestions(
            principal,
            query = null,
            language = "ko",
            limit = 20,
            offset = 0,
        )
        val commentsResponse = service.getComments(
            id = 101,
            limit = 20,
            offset = 0,
            principal = principal,
        )

        assertThat(questionsResponse.questions.map { it.author?.id }).containsExactly(11)
        assertThat(commentsResponse.comments.map { it.author.id }).containsExactly(11)
    }

    @Test
    fun `blocking a user is idempotent and can be reversed`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")

        assertThat(service.setUserBlocked(principal, userId = 10, blocked = true).blocked).isTrue()
        assertThat(service.setUserBlocked(principal, userId = 10, blocked = true).blocked).isTrue()
        assertThat(userBlocks.rows).hasSize(1)

        assertThat(service.setUserBlocked(principal, userId = 10, blocked = false).blocked).isFalse()
        assertThat(userBlocks.rows).isEmpty()
    }

    @Test
    fun `liking a public question increments stats without recounting likes`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 3, commentCount = 1, viewCount = 20)

        val response = service.setLike(principal, id = 100, liked = true)

        assertThat(response.likeCount).isEqualTo(4)
        assertThat(response.isLikedByMe).isTrue()
        assertThat(questionStats.incrementLikeCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
        assertThat(reactionPublisher.events).containsExactly("QUESTION_LIKED:100:7")
        val notification = notificationPublisher.rows.single()
        assertThat(notification.eventId).isEqualTo("question-like-100-7")
        assertThat(notification.userId).isEqualTo(10)
        assertThat(notification.actorUserId).isEqualTo(7)
        assertThat(notification.threadType).isEqualTo("question")
        assertThat(notification.threadId).isEqualTo("100")
        assertThat(notification.shouldPush).isFalse()
    }

    @Test
    fun `unliking a public question publishes the unlike event only when state changes`(): Unit = runBlocking {
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 1)
        likes.rows += QuestionLikeEntity(questionId = 100, userId = principal.userId)

        val response = service.setLike(principal, id = 100, liked = false)

        assertThat(response.isLikedByMe).isFalse()
        assertThat(response.likeCount).isZero()
        assertThat(reactionPublisher.events).containsExactly("QUESTION_UNLIKED:100:7")
    }

    @Test
    fun `commenting on another user's question publishes push eligible thread notification`(): Unit = runBlocking {
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
        assertThat(notification.deepLink).isEqualTo("buddystudy://public/questions/100")
        assertThat(notification.shouldPush).isTrue()
        assertThat(notification.title).isEqualTo("댓글")
        assertThat(reactionPublisher.events).containsExactly("QUESTION_COMMENTED:100:1:7")
    }

    @Test
    fun `deleting my comment publishes the comment deleted event`() = runBlocking<Unit> {
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        comments.rows += QuestionCommentEntity(id = 5, questionId = 100, userId = principal.userId, body = "삭제할 댓글")

        service.deleteComment(principal, id = 100, commentId = 5)

        assertThat(comments.rows.single().deletedAt).isNotNull()
        assertThat(reactionPublisher.events).containsExactly("QUESTION_COMMENT_DELETED:100:5:7")
    }

    @Test
    fun `empty comment page skips author lookup`(): Unit = runBlocking {
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

        override suspend fun save(entity: UserEntity): UserEntity = entity
        override suspend fun findById(id: Long): UserEntity? {
            findByIdCalls += 1
            return rows.firstOrNull { it.id == id }
        }

        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> {
            findAllByIdCalls += 1
            val idSet = ids.toSet()
            return rows.filter { it.id in idSet }.toMutableList()
        }

        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionPort : QuestionPort {
        val rows = mutableListOf<QuestionEntity>()
        override suspend fun save(entity: QuestionEntity): QuestionEntity = entity
        override suspend fun findQuestionById(id: Long): QuestionEntity? = null
        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> = emptyList()
        override suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun countPendingForStudy(studyId: Long): Long = 0
        override suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> = emptyMap()
        override suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = PageImpl(rows.sortedByDescending { it.createdAt })
        override suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredById(id: Long): QuestionEntity? = rows.firstOrNull { it.id == id }
        override suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = rows.filter { it.id in ids }
        override suspend fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByUserId(userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByStudyId(studyId: Long, userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int = 0
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        var incrementLikeCalls = 0

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
        override suspend fun incrementLike(questionId: Long, delta: Int, now: Instant): Int {
            incrementLikeCalls += 1
            val row = rows.firstOrNull { it.questionId == questionId } ?: return 0
            row.likeCount = maxOf(0, row.likeCount + delta)
            row.updatedAt = now
            return 1
        }
        override suspend fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeQuestionLikePort : QuestionLikePort {
        val rows = mutableListOf<QuestionLikeEntity>()
        var existsCalls = 0
        var findLikedQuestionIdsCalls = 0

        override suspend fun save(entity: QuestionLikeEntity): QuestionLikeEntity {
            rows += entity
            return entity
        }
        override suspend fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean {
            existsCalls += 1
            return rows.any { it.questionId == questionId && it.userId == userId }
        }

        override suspend fun findLikedQuestionIds(userId: Long, questionIds: Collection<Long>): Set<Long> {
            findLikedQuestionIdsCalls += 1
            return rows.filter { it.userId == userId && it.questionId in questionIds }.map { it.questionId }.toSet()
        }

        override suspend fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long {
            val removed = rows.removeIf { it.questionId == questionId && it.userId == userId }
            return if (removed) 1 else 0
        }
    }

    private class FakeQuestionCommentPort : QuestionCommentPort {
        val rows = mutableListOf<QuestionCommentEntity>()
        private var nextId = 1L
        override suspend fun save(entity: QuestionCommentEntity): QuestionCommentEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            if (entity !in rows) rows += entity
            return entity
        }
        override suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(
            id: Long,
            questionId: Long,
        ): QuestionCommentEntity? = rows.firstOrNull {
            it.id == id && it.questionId == questionId && it.deletedAt == null
        }
        override suspend fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            questionId: Long,
            pageable: Pageable,
        ): Page<QuestionCommentEntity> = PageImpl(rows.filter { it.questionId == questionId && it.deletedAt == null })
    }

    private class FakeReportPort : ReportPort {
        override suspend fun save(entity: ReportEntity): ReportEntity = entity
    }

    private class FakeFeedbackPort : FeedbackPort {
        override suspend fun save(entity: FeedbackEntity): FeedbackEntity = entity
    }

    private class FakeUserBlockPort : UserBlockPort {
        val rows = mutableListOf<UserBlockEntity>()

        override suspend fun save(entity: UserBlockEntity): UserBlockEntity {
            if (entity.id == 0L) entity.id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
            rows += entity
            return entity
        }

        override suspend fun exists(blockerUserId: Long, blockedUserId: Long): Boolean =
            rows.any { it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId }

        override suspend fun findBlockedUserIds(blockerUserId: Long): Set<Long> =
            rows.filter { it.blockerUserId == blockerUserId }.map { it.blockedUserId }.toSet()

        override suspend fun delete(blockerUserId: Long, blockedUserId: Long): Long {
            val removed = rows.removeIf {
                it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId
            }
            return if (removed) 1 else 0
        }
    }

    private class FakeReactionPublisher : PublicQuestionReactionPublishPort {
        val events = mutableListOf<String>()

        override suspend fun publishViewed(
            questionId: Long,
            userId: Long?,
            localization: PublicQuestionViewLocalization?,
        ): Boolean {
            events += "CONTENT_VIEWED:$questionId:$userId"
            return true
        }

        override suspend fun publishLiked(questionId: Long, userId: Long): Boolean {
            events += "QUESTION_LIKED:$questionId:$userId"
            return true
        }

        override suspend fun publishUnliked(questionId: Long, userId: Long): Boolean {
            events += "QUESTION_UNLIKED:$questionId:$userId"
            return true
        }

        override suspend fun publishCommented(questionId: Long, commentId: Long, userId: Long): Boolean {
            events += "QUESTION_COMMENTED:$questionId:$commentId:$userId"
            return true
        }

        override suspend fun publishCommentDeleted(questionId: Long, commentId: Long, userId: Long): Boolean {
            events += "QUESTION_COMMENT_DELETED:$questionId:$commentId:$userId"
            return true
        }
    }

    private class FakeNotificationPublisher : PublishNotificationUseCase {
        val rows = mutableListOf<NotificationRequestCommand>()
        override suspend fun publish(command: NotificationRequestCommand): Boolean {
            rows += command
            return true
        }
    }
}
