package com.buddystuddy.backend

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.community.adapter.outbound.persistence.QuestionCommentRepository
import com.buddystuddy.backend.community.adapter.outbound.persistence.QuestionLikeRepository
import com.buddystuddy.backend.community.adapter.outbound.persistence.ReportRepository
import com.buddystuddy.backend.community.application.service.CommunityService
import com.buddystuddy.backend.domain.*
import com.buddystuddy.backend.dto.ReportQuestionRequest
import com.buddystuddy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.service.StudyService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-public-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
@Transactional
class CommunityStudyServiceTest {
    @Autowired lateinit var community: CommunityService
    @Autowired lateinit var study: StudyService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var stats: QuestionStatsPort
    @Autowired lateinit var likes: QuestionLikeRepository
    @Autowired lateinit var comments: QuestionCommentRepository
    @Autowired lateinit var reports: ReportRepository

    private lateinit var author: UserEntity
    private lateinit var viewer: UserEntity
    private lateinit var hiddenAuthor: UserEntity
    private lateinit var principal: Principal
    private val now: Instant = Instant.parse("2026-06-08T00:00:00Z")

    @BeforeEach
    fun setUp() {
        author = users.save(user("author", "Author", allowPublic = true))
        viewer = users.save(user("viewer", "Viewer", allowPublic = true))
        hiddenAuthor = users.save(user("hidden", "Hidden", allowPublic = false))
        principal = Principal(userId = viewer.id, deviceId = "device-viewer", sessionId = 1, anonymous = false)
    }

    @Test
    fun `public questions only include answered public records from authors who allow public questions`() {
        val visible = answeredPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(4))
        answeredPublicQuestion(author, "Kotlin", createdAt = now.plusSeconds(3), publicQuestion = false)
        pendingPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(2))
        answeredPublicQuestion(hiddenAuthor, "SwiftUI", createdAt = now.plusSeconds(1))
        answeredPublicQuestion(author, "SwiftUI", createdAt = now, deletedAt = now.plusSeconds(10))

        val response = community.publicQuestions(null, "swift", limit = 10, offset = 0)

        assertThat(response.totalCount).isEqualTo(1)
        val result = response.questions.single()
        assertThat(result.id).isEqualTo(visible.id.toString())
        assertThat(result.author?.displayName).isEqualTo("Author")
        assertThat(result.answer).isEqualTo("Answer for SwiftUI")
        assertThat(result.gradingResult?.score).isEqualTo(91)
    }

    @Test
    fun `public questions without topic search return answered public records`() {
        val newest = answeredPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(2))
        val older = answeredPublicQuestion(author, "Kotlin", createdAt = now.plusSeconds(1))
        pendingPublicQuestion(author, "SwiftUI", createdAt = now)

        val response = community.publicQuestions(null, null, limit = 10, offset = 0)

        assertThat(response.totalCount).isEqualTo(2)
        assertThat(response.questions.map { it.id }).containsExactly(newest.id.toString(), older.id.toString())
    }

    @Test
    fun `public question detail includes stats author and viewer like state`() {
        val q = answeredPublicQuestion(author, "SwiftUI")
        stats.save(QuestionStatsEntity(questionId = q.id, likeCount = 7, commentCount = 2, viewCount = 11))
        likes.save(QuestionLikeEntity(questionId = q.id, userId = viewer.id))

        val response = community.publicQuestion(principal, q.id)

        assertThat(response.id).isEqualTo(q.id.toString())
        assertThat(response.author?.displayName).isEqualTo("Author")
        assertThat(response.likeCount).isEqualTo(7)
        assertThat(response.commentCount).isEqualTo(2)
        assertThat(response.viewCount).isEqualTo(11)
        assertThat(response.isLikedByMe).isTrue()
    }

    @Test
    fun `public detail rejects private pending deleted or globally hidden questions`() {
        val privateQuestion = answeredPublicQuestion(author, "Private", publicQuestion = false)
        val pending = pendingPublicQuestion(author, "Pending")
        val deleted = answeredPublicQuestion(author, "Deleted", deletedAt = now)
        val hidden = answeredPublicQuestion(hiddenAuthor, "Hidden")

        listOf(privateQuestion, pending, deleted, hidden).forEach {
            assertRecordNotFound { community.publicQuestion(principal, it.id) }
        }
    }

    @Test
    fun `like is idempotent and unlike does not go below zero`() {
        val q = answeredPublicQuestion(author, "SwiftUI")
        stats.save(QuestionStatsEntity(questionId = q.id, likeCount = 3))

        val liked = community.setLike(principal, q.id, liked = true)
        val likedAgain = community.setLike(principal, q.id, liked = true)
        val unliked = community.setLike(principal, q.id, liked = false)
        val unlikedAgain = community.setLike(principal, q.id, liked = false)

        assertThat(liked.likeCount).isEqualTo(4)
        assertThat(likedAgain.likeCount).isEqualTo(3)
        assertThat(unliked.likeCount).isEqualTo(2)
        assertThat(unlikedAgain.likeCount).isEqualTo(3)
        assertThat(likes.existsByQuestionIdAndUserId(q.id, viewer.id)).isFalse()
    }

    @Test
    fun `like rejects records that are not public answered questions`() {
        val q = pendingPublicQuestion(author, "SwiftUI")

        assertRecordNotFound { community.setLike(principal, q.id, liked = true) }
        assertThat(likes.findAll()).isEmpty()
    }

    @Test
    fun `comment truncates body and comments page returns comment authors`() {
        val q = answeredPublicQuestion(author, "SwiftUI")
        val longBody = "x".repeat(1_050)

        val saved = community.comment(principal, q.id, longBody)
        val page = community.comments(q.id, limit = 20, offset = 0)

        assertThat(saved.body).hasSize(1_000)
        assertThat(saved.author.displayName).isEqualTo("Viewer")
        assertThat(page.totalCount).isEqualTo(1)
        val result = page.comments.single()
        assertThat(result.body).hasSize(1_000)
        assertThat(result.author.displayName).isEqualTo("Viewer")
    }

    @Test
    fun `comment and comments reject non public answered records`() {
        val q = pendingPublicQuestion(author, "SwiftUI")

        assertRecordNotFound { community.comment(principal, q.id, "body") }
        assertRecordNotFound { community.comments(q.id, limit = 10, offset = 0) }
    }

    @Test
    fun `report is saved only for public answered records`() {
        val publicQuestion = answeredPublicQuestion(author, "SwiftUI")
        val privateQuestion = answeredPublicQuestion(author, "Private", publicQuestion = false)

        community.report(principal, publicQuestion.id, ReportQuestionRequest(reason = "spam", message = "bad"))
        assertRecordNotFound { community.report(principal, privateQuestion.id, ReportQuestionRequest(reason = "spam")) }

        val result = reports.findAll().single()
        assertThat(result.questionId).isEqualTo(publicQuestion.id)
        assertThat(result.reporterUserId).isEqualTo(viewer.id)
        assertThat(result.reason).isEqualTo("spam")
        assertThat(result.message).isEqualTo("bad")
    }

    @Test
    fun `records exclude pending questions while pending endpoint returns them`() {
        val graded = answeredPublicQuestion(viewer, "SwiftUI")
        val pending = pendingPublicQuestion(viewer, "Kotlin")

        val records = study.records(principal, limit = 10, offset = 0)
        val pendingRecords = study.pending(principal, limit = 10, offset = 0)

        assertThat(records.records.map { it.id }).containsExactly(graded.id.toString())
        assertThat(pendingRecords.records.map { it.id }).containsExactly(pending.id.toString())
    }

    @Test
    fun `publicity can only publish already graded questions`() {
        val graded = answeredPublicQuestion(viewer, "SwiftUI", publicQuestion = false)
        val pending = pendingPublicQuestion(viewer, "Kotlin")

        val published = study.publicity(principal, graded.id, isPublic = true)
        val pendingPublish = study.publicity(principal, pending.id, isPublic = true)

        assertThat(published.isPublic).isTrue()
        assertThat(pendingPublish.isPublic).isFalse()
        assertThat(questions.findById(graded.id).orElseThrow().publicQuestion).isTrue()
        assertThat(questions.findById(pending.id).orElseThrow().publicQuestion).isFalse()
    }

    private fun user(providerId: String, name: String, allowPublic: Boolean): UserEntity =
        UserEntity(
            provider = "EMAIL",
            providerId = "$providerId@example.com",
            email = "$providerId@example.com",
            status = "ACTIVE",
            displayName = name,
            allowPublicQuestions = allowPublic,
            createdAt = now,
            updatedAt = now,
        )

    private fun answeredPublicQuestion(
        user: UserEntity,
        topic: String,
        createdAt: Instant = now,
        publicQuestion: Boolean = true,
        deletedAt: Instant? = null,
    ): QuestionEntity =
        questions.save(
            QuestionEntity(
                deviceId = "device-${user.id}",
                userId = user.id,
                question = "Question for $topic",
                hint = "Hint for $topic",
                topic = topic,
                difficultyLevel = 6,
                scheduledFor = createdAt,
                sentAt = createdAt,
                status = "graded",
                answer = "Answer for $topic",
                score = 91,
                correct = true,
                feedback = "Good",
                explanation = "Because",
                answeredAt = createdAt.plusSeconds(30),
                gradedAt = createdAt.plusSeconds(40),
                source = "manual",
                publicQuestion = publicQuestion,
                deletedAt = deletedAt,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        )

    private fun pendingPublicQuestion(user: UserEntity, topic: String, createdAt: Instant = now): QuestionEntity =
        questions.save(
            QuestionEntity(
                deviceId = "device-${user.id}",
                userId = user.id,
                question = "Pending question for $topic",
                topic = topic,
                difficultyLevel = 4,
                scheduledFor = createdAt,
                sentAt = createdAt,
                status = "ungraded",
                source = "scheduled",
                publicQuestion = true,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        )

    private fun assertRecordNotFound(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
    }
}
