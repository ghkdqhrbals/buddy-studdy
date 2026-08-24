package com.buddystudy.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.adapter.outbound.persistence.QuestionCommentRepository
import com.buddystudy.backend.community.adapter.outbound.persistence.FeedbackRepository
import com.buddystudy.backend.community.adapter.outbound.persistence.QuestionLikeRepository
import com.buddystudy.backend.community.adapter.outbound.persistence.ReportRepository
import com.buddystudy.backend.community.adapter.outbound.persistence.UserBlockRepository
import com.buddystudy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystudy.backend.community.application.port.inbound.SubmitFeedbackCommand
import com.buddystudy.backend.community.application.service.CommunityService
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.service.StudySyncService
import com.buddystudy.backend.study.application.service.StudyService
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.UserBlockEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class CommunityStudyServiceTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var databaseClient: DatabaseClient
    @Autowired lateinit var community: CommunityService
    @Autowired lateinit var study: StudyService
    @Autowired lateinit var studySync: StudySyncService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var questions: QuestionRepository
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var stats: QuestionStatsPort
    @Autowired lateinit var likes: QuestionLikeRepository
    @Autowired lateinit var comments: QuestionCommentRepository
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var feedbacks: FeedbackRepository
    @Autowired lateinit var userBlocks: UserBlockRepository

    private lateinit var author: UserEntity
    private lateinit var viewer: UserEntity
    private lateinit var hiddenAuthor: UserEntity
    private lateinit var principal: Principal
    private val now: Instant = Instant.parse("2026-06-08T00:00:00Z")

    @BeforeEach
    fun setUp() = runBlocking {
        listOf(
            "feedbacks",
            "reports",
            "user_blocks",
            "question_comments",
            "question_likes",
            "question_stats",
            "questions",
            "studies",
            "user_roles",
            "user_term_agreements",
            "users",
        ).forEach { table ->
            databaseClient.sql("delete from $table").fetch().rowsUpdated().awaitSingle()
        }
        author = users.save(user("author", "Author", allowPublic = true))
        viewer = users.save(user("viewer", "Viewer", allowPublic = true))
        hiddenAuthor = users.save(user("hidden", "Hidden", allowPublic = false))
        principal = Principal(userId = viewer.id, deviceId = "device-viewer", sessionId = 1, anonymous = false)
    }

    @Test
    fun `public questions only include answered public records from authors who allow public questions`(): Unit = runBlocking {
        val visible = answeredPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(4))
        answeredPublicQuestion(author, "Kotlin", createdAt = now.plusSeconds(3), publicQuestion = false)
        pendingPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(2))
        answeredPublicQuestion(hiddenAuthor, "SwiftUI", createdAt = now.plusSeconds(1))
        answeredPublicQuestion(author, "SwiftUI", createdAt = now, deletedAt = now.plusSeconds(10))
        val response = community.getPublicQuestions(null, null, language = "ko", limit = 10, offset = 0)

        assertThat(response.totalCount).isEqualTo(1)
        val result = response.questions.single()
        assertThat(result.id).isEqualTo(visible.id.toString())
        assertThat(result.author?.displayName).isEqualTo("Author")
        assertThat(result.answer).isEqualTo("Answer for SwiftUI")
        assertThat(result.gradingResult?.score).isEqualTo(91)
    }

    @Test
    fun `public questions without topic search return answered public records`(): Unit = runBlocking {
        val newest = answeredPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(2))
        val older = answeredPublicQuestion(author, "Kotlin", createdAt = now.plusSeconds(1))
        pendingPublicQuestion(author, "SwiftUI", createdAt = now)
        val response = community.getPublicQuestions(null, null, language = "ko", limit = 10, offset = 0)

        assertThat(response.totalCount).isEqualTo(2)
        assertThat(response.questions.map { it.id }).containsExactly(newest.id.toString(), older.id.toString())
    }

    @Test
    fun `liked public questions page by like time with an exact visible total`(): Unit = runBlocking {
        val blockedAuthor = users.save(user("blocked", "Blocked", allowPublic = true))
        val newestLike = answeredPublicQuestion(author, "Newest liked match", createdAt = now.plusSeconds(1))
        val middleLike = answeredPublicQuestion(author, "Middle liked", createdAt = now.plusSeconds(3))
        val oldestLike = answeredPublicQuestion(author, "Oldest liked", createdAt = now.plusSeconds(2))
        val private = answeredPublicQuestion(author, "Private liked", publicQuestion = false)
        val deleted = answeredPublicQuestion(author, "Deleted liked", deletedAt = now.plusSeconds(100))
        val hidden = answeredPublicQuestion(hiddenAuthor, "Hidden-author liked")
        val blocked = answeredPublicQuestion(blockedAuthor, "Blocked-author liked")
        val ungradedWithAnswer = pendingPublicQuestion(author, "Ungraded liked").also {
            it.answer = "Saved but not graded"
            questions.save(it)
        }
        val blankAnswer = answeredPublicQuestion(author, "Blank-answer liked").also {
            it.answer = "  "
            questions.save(it)
        }
        listOf(
            newestLike to now.plusSeconds(30),
            middleLike to now.plusSeconds(20),
            oldestLike to now.plusSeconds(10),
            private to now.plusSeconds(40),
            deleted to now.plusSeconds(50),
            hidden to now.plusSeconds(60),
            blocked to now.plusSeconds(70),
            ungradedWithAnswer to now.plusSeconds(80),
            blankAnswer to now.plusSeconds(90),
        ).forEach { (question, likedAt) ->
            likes.save(QuestionLikeEntity(questionId = question.id, userId = viewer.id, createdAt = likedAt))
        }
        val likedBySomeoneElse = answeredPublicQuestion(author, "Someone else's like")
        likes.save(QuestionLikeEntity(questionId = likedBySomeoneElse.id, userId = author.id, createdAt = now.plusSeconds(100)))
        userBlocks.save(UserBlockEntity(blockerUserId = viewer.id, blockedUserId = blockedAuthor.id, createdAt = now))

        val page = community.getLikedPublicQuestions(
            principal = principal,
            query = null,
            language = "ko",
            limit = 20,
            offset = 2,
        )
        val searched = community.getLikedPublicQuestions(
            principal = principal,
            query = "  newest liked match  ",
            language = "ko",
            view = "original",
            limit = 20,
            offset = 0,
        )

        assertThat(page.totalCount).isEqualTo(3)
        assertThat(page.questions.map { it.id }).containsExactly(oldestLike.id.toString())
        assertThat(page.questions.single().isLikedByMe).isTrue()
        assertThat(page.items).hasSize(1).allMatch { it.question != null && it.advertisement == null }
        assertThat(searched.totalCount).isEqualTo(1)
        assertThat(searched.questions.map { it.id }).containsExactly(newestLike.id.toString())
    }

    @Test
    fun `public questions include saved answers when grading has no score`(): Unit = runBlocking {
        val failedGrading = answeredPublicQuestion(
            author,
            "Async grading",
            createdAt = now.plusSeconds(1),
            score = null,
            gradingStatus = "FAILED",
        )
        pendingPublicQuestion(author, "No answer", createdAt = now)

        val response = community.getPublicQuestions(null, null, language = "ko", limit = 10, offset = 0)

        assertThat(response.totalCount).isEqualTo(1)
        val result = response.questions.single()
        assertThat(result.id).isEqualTo(failedGrading.id.toString())
        assertThat(result.answer).isEqualTo("Answer for Async grading")
        assertThat(result.gradingResult).isNull()
    }

    @Test
    fun `public detail allows saved answer when grading has no score`(): Unit = runBlocking {
        val failedGrading = answeredPublicQuestion(
            author,
            "Async grading",
            score = null,
            gradingStatus = "FAILED",
        )

        val response = community.getPublicQuestion(principal, failedGrading.id, language = "ko")

        assertThat(response.id).isEqualTo(failedGrading.id.toString())
        assertThat(response.answer).isEqualTo("Answer for Async grading")
        assertThat(response.gradingResult).isNull()
    }

    @Test
    fun `public questions v2 reads canonical questions and only returns public answered records`(): Unit = runBlocking {
        val newest = answeredPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(2))
        val older = answeredPublicQuestion(author, "Kotlin", createdAt = now.plusSeconds(1))
        val private = answeredPublicQuestion(author, "Private", createdAt = now.plusSeconds(3), publicQuestion = false)
        val pending = pendingPublicQuestion(author, "SwiftUI", createdAt = now.plusSeconds(4))
        val hidden = answeredPublicQuestion(hiddenAuthor, "Hidden", createdAt = now.plusSeconds(5))
        val response = community.getPublicQuestionsV2(principal = null, query = null, language = "ko", limit = 10, offset = 0)

        assertThat(response.totalCount).isEqualTo(2)
        assertThat(response.questions.map { it.id }).containsExactly(newest.id.toString(), older.id.toString())
    }

    @Test
    fun `public question detail includes stats author and viewer like state`(): Unit = runBlocking {
        val q = answeredPublicQuestion(author, "SwiftUI")
        stats.save(QuestionStatsEntity(questionId = q.id, likeCount = 7, commentCount = 2, viewCount = 11))
        likes.save(QuestionLikeEntity(questionId = q.id, userId = viewer.id))

        val response = community.getPublicQuestion(principal, q.id, language = "ko")

        assertThat(response.id).isEqualTo(q.id.toString())
        assertThat(response.author?.displayName).isEqualTo("Author")
        assertThat(response.likeCount).isEqualTo(7)
        assertThat(response.commentCount).isEqualTo(2)
        assertThat(response.viewCount).isEqualTo(11)
        assertThat(response.isLikedByMe).isTrue()
    }

    @Test
    fun `public detail rejects private pending deleted or globally hidden questions`(): Unit = runBlocking {
        val privateQuestion = answeredPublicQuestion(author, "Private", publicQuestion = false)
        val pending = pendingPublicQuestion(author, "Pending")
        val deleted = answeredPublicQuestion(author, "Deleted", deletedAt = now)
        val hidden = answeredPublicQuestion(hiddenAuthor, "Hidden")

        listOf(privateQuestion, pending, deleted, hidden).forEach {
            assertRecordNotFound { community.getPublicQuestion(principal, it.id, language = "ko") }
        }
    }

    @Test
    fun `like is idempotent and unlike does not go below zero`(): Unit = runBlocking {
        val q = answeredPublicQuestion(author, "SwiftUI")
        stats.save(QuestionStatsEntity(questionId = q.id, likeCount = 3))

        val liked = community.setLike(principal, q.id, liked = true)
        val likedAgain = community.setLike(principal, q.id, liked = true)
        val unliked = community.setLike(principal, q.id, liked = false)
        val unlikedAgain = community.setLike(principal, q.id, liked = false)

        assertThat(liked.likeCount).isEqualTo(4)
        assertThat(likedAgain.likeCount).isEqualTo(4)
        assertThat(unliked.likeCount).isEqualTo(3)
        assertThat(unlikedAgain.likeCount).isEqualTo(3)
        assertThat(likes.existsByQuestionIdAndUserId(q.id, viewer.id)).isFalse()
        assertThat(stats.findById(q.id)!!.likeCount).isEqualTo(3)
    }

    @Test
    fun `like rejects records that are not public answered questions`(): Unit = runBlocking {
        val q = pendingPublicQuestion(author, "SwiftUI")

        assertRecordNotFound { community.setLike(principal, q.id, liked = true) }
        assertThat(likes.findAll().toList()).isEmpty()
    }

    @Test
    fun `comment truncates body and comments page returns comment authors`(): Unit = runBlocking {
        val q = answeredPublicQuestion(author, "SwiftUI")
        val longBody = "x".repeat(1_050)

        val saved = community.createComment(principal, q.id, longBody)
        val page = community.getComments(q.id, limit = 20, offset = 0)

        assertThat(saved.body).hasSize(1_000)
        assertThat(saved.author.displayName).isEqualTo("Viewer")
        assertThat(page.totalCount).isEqualTo(1)
        assertThat(stats.findById(q.id)!!.commentCount).isEqualTo(1)
        val result = page.comments.single()
        assertThat(result.body).hasSize(1_000)
        assertThat(result.author.displayName).isEqualTo("Viewer")
    }

    @Test
    fun `comments page returns oldest comments first so the newest comment appears at the bottom`(): Unit = runBlocking {
        val q = answeredPublicQuestion(author, "SwiftUI")
        val first = community.createComment(principal, q.id, "first")
        val second = community.createComment(principal, q.id, "second")
        comments.save(comments.findById(first.id.toLong())!!.apply {
            createdAt = now.plusSeconds(1)
            updatedAt = now.plusSeconds(1)
        })
        comments.save(comments.findById(second.id.toLong())!!.apply {
            createdAt = now.plusSeconds(2)
            updatedAt = now.plusSeconds(2)
        })

        val page = community.getComments(q.id, limit = 20, offset = 0)

        assertThat(page.comments.map { it.body }).containsExactly("first", "second")
    }

    @Test
    fun `comment owner can delete comment and deleted comments are hidden`(): Unit = runBlocking {
        val q = answeredPublicQuestion(author, "SwiftUI")
        val saved = community.createComment(principal, q.id, "delete me")

        val response = community.deleteComment(principal, q.id, saved.id.toLong())
        val page = community.getComments(q.id, limit = 20, offset = 0)

        assertThat(response.ok).isTrue()
        assertThat(response.id).isEqualTo(saved.id)
        assertThat(page.totalCount).isZero()
        assertThat(stats.findById(q.id)!!.commentCount).isZero()
        assertThat(comments.findById(saved.id.toLong())!!.deletedAt).isNotNull()
    }

    @Test
    fun `comment delete rejects other users and missing comments`(): Unit = runBlocking {
        val q = answeredPublicQuestion(author, "SwiftUI")
        val saved = community.createComment(principal, q.id, "not yours")
        val otherPrincipal = Principal(userId = author.id, deviceId = "device-author", sessionId = 2, anonymous = false)

        assertRecordNotFound { community.deleteComment(otherPrincipal, q.id, saved.id.toLong()) }
        assertRecordNotFound { community.deleteComment(principal, q.id, 999_999) }
        assertThat(comments.findById(saved.id.toLong())!!.deletedAt).isNull()
    }

    @Test
    fun `comment and comments reject non public answered records`(): Unit = runBlocking {
        val q = pendingPublicQuestion(author, "SwiftUI")

        assertRecordNotFound { community.createComment(principal, q.id, "body") }
        assertRecordNotFound { community.deleteComment(principal, q.id, 1) }
        assertRecordNotFound { community.getComments(q.id, limit = 10, offset = 0) }
    }

    @Test
    fun `report is saved only for public answered records`(): Unit = runBlocking {
        val publicQuestion = answeredPublicQuestion(author, "SwiftUI")
        val privateQuestion = answeredPublicQuestion(author, "Private", publicQuestion = false)

        community.reportQuestion(principal, publicQuestion.id, ReportQuestionCommand(reason = "spam", message = "bad"))
        assertRecordNotFound { community.reportQuestion(principal, privateQuestion.id, ReportQuestionCommand(reason = "spam")) }

        val result = reports.findAll().single()
        assertThat(result.questionId).isEqualTo(publicQuestion.id)
        assertThat(result.reporterUserId).isEqualTo(viewer.id)
        assertThat(result.reason).isEqualTo("spam")
        assertThat(result.message).isEqualTo("bad")
    }

    @Test
    fun `blocked users disappear from public questions details and comments until unblocked`(): Unit = runBlocking {
        val blockedQuestion = answeredPublicQuestion(author, topic = "Blocked author", createdAt = now.plusSeconds(2))
        hiddenAuthor.allowPublicQuestions = true
        users.save(hiddenAuthor)
        val visibleQuestion = answeredPublicQuestion(hiddenAuthor, topic = "Visible author", createdAt = now.plusSeconds(1))
        comments.save(
            QuestionCommentEntity(
                questionId = visibleQuestion.id,
                userId = author.id,
                body = "blocked comment",
                createdAt = now,
                updatedAt = now,
            ),
        )
        comments.save(
            QuestionCommentEntity(
                questionId = visibleQuestion.id,
                userId = hiddenAuthor.id,
                body = "visible comment",
                createdAt = now.plusSeconds(1),
                updatedAt = now.plusSeconds(1),
            ),
        )

        community.setUserBlocked(principal, author.id, blocked = true)

        val page = community.getPublicQuestions(
            principal = principal,
            query = null,
            language = "ko",
            limit = 1,
            offset = 0,
        )
        val commentPage = community.getComments(
            id = visibleQuestion.id,
            limit = 1,
            offset = 0,
            principal = principal,
        )

        assertThat(page.questions.map { it.id }).containsExactly(visibleQuestion.id.toString())
        assertThat(page.totalCount).isEqualTo(1)
        assertThat(commentPage.comments.map { it.body }).containsExactly("visible comment")
        assertThat(commentPage.totalCount).isEqualTo(1)
        assertRecordNotFound { community.getPublicQuestion(principal, blockedQuestion.id, language = "ko") }
        assertRecordNotFound {
            community.getComments(
                id = blockedQuestion.id,
                language = "ko",
                limit = 1,
                offset = 0,
                principal = principal,
            )
        }
        assertThat(userBlocks.existsByBlockerUserIdAndBlockedUserId(viewer.id, author.id)).isTrue()

        community.setUserBlocked(principal, author.id, blocked = false)

        assertThat(community.getPublicQuestion(principal, blockedQuestion.id, language = "ko").id)
            .isEqualTo(blockedQuestion.id.toString())
        assertThat(
            community.getComments(
                id = visibleQuestion.id,
                limit = 20,
                offset = 0,
                principal = principal,
            ).comments.map { it.body },
        ).containsExactly("blocked comment", "visible comment")
        assertThat(userBlocks.existsByBlockerUserIdAndBlockedUserId(viewer.id, author.id)).isFalse()
    }

    @Test
    fun `concurrent duplicate block requests create one relationship`(): Unit = runBlocking {
        val responses = coroutineScope {
            List(8) {
                async(Dispatchers.Default) {
                    community.setUserBlocked(principal, author.id, blocked = true)
                }
            }.awaitAll()
        }

        assertThat(responses).allMatch { it.blocked && it.userId == author.id }
        assertThat(userBlocks.count()).isEqualTo(1)
    }

    @Test
    fun `feedback is trimmed and stored independently from reports`(): Unit = runBlocking {
        val response = community.submitFeedback(
            principal = principal,
            deviceId = null,
            command = SubmitFeedbackCommand("  검색 결과 정렬을 개선해 주세요.  "),
        )

        val feedback = feedbacks.findAll().single()
        assertThat(response.id).isEqualTo(feedback.id)
        assertThat(response.createdAt).isEqualTo(feedback.createdAt)
        assertThat(feedback.userId).isEqualTo(viewer.id)
        assertThat(feedback.deviceId).isEqualTo(principal.deviceId)
        assertThat(feedback.content).isEqualTo("검색 결과 정렬을 개선해 주세요.")
        assertThat(reports.count()).isZero()
    }

    @Test
    fun `records exclude pending questions while pending endpoint returns them`(): Unit = runBlocking {
        val graded = answeredPublicQuestion(viewer, "SwiftUI")
        val pending = pendingPublicQuestion(viewer, "Kotlin")

        val records = study.records(principal, limit = 10, offset = 0)
        val pendingRecords = study.pending(principal, limit = 10, offset = 0)

        assertThat(records.records.map { it.id }).containsExactly(graded.id.toString())
        assertThat(pendingRecords.records.map { it.id }).containsExactly(pending.id.toString())
    }

    @Test
    fun `study page includes the current pending question for each study`(): Unit = runBlocking {
        val room = studies.save(
            StudyEntity(
                deviceId = principal.deviceId,
                userId = principal.userId,
                topic = "Redis",
                difficultyLevel = 2,
                createdAt = now,
                updatedAt = now,
            )
        )
        val pending = pendingPublicQuestion(viewer, "Redis").also {
            it.studyId = room.id
            questions.save(it)
        }

        val page = studySync.study(principal, limit = 10, offset = 0)

        assertThat(page.studies).hasSize(1)
        assertThat(page.studies.single().topic).isEqualTo("Redis")
        assertThat(page.studies.single().pendingQuestion?.id).isEqualTo(pending.id.toString())
        assertThat(page.studies.single().pendingQuestion?.question?.question).isEqualTo("Pending question for Redis")
    }

    @Test
    fun `publicity can only publish already graded questions`(): Unit = runBlocking {
        val graded = answeredPublicQuestion(viewer, "SwiftUI", publicQuestion = false)
        val pending = pendingPublicQuestion(viewer, "Kotlin")

        val published = study.publicity(principal, graded.id, isPublic = true)
        val pendingPublish = study.publicity(principal, pending.id, isPublic = true)

        assertThat(published.isPublic).isTrue()
        assertThat(pendingPublish.isPublic).isFalse()
        assertThat(questions.findById(graded.id)!!.publicQuestion).isTrue()
        assertThat(questions.findById(pending.id)!!.publicQuestion).isFalse()
    }

    private fun user(providerId: String, name: String, allowPublic: Boolean): UserEntity =
        UserEntity(
            provider = UserProvider.EMAIL,
            providerId = "$providerId@example.com",
            email = "$providerId@example.com",
            status = UserStatus.ACTIVE,
            displayName = name,
            allowPublicQuestions = allowPublic,
            createdAt = now,
            updatedAt = now,
        )

    private suspend fun answeredPublicQuestion(
        user: UserEntity,
        topic: String,
        createdAt: Instant = now,
        publicQuestion: Boolean = true,
        deletedAt: Instant? = null,
        score: Int? = 91,
        gradingStatus: String? = null,
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
                status = QuestionStatus.GRADED,
                answer = "Answer for $topic",
                score = score,
                correct = score?.let { true },
                feedback = score?.let { "Good" },
                explanation = score?.let { "Because" },
                answeredAt = createdAt.plusSeconds(30),
                gradedAt = score?.let { createdAt.plusSeconds(40) },
                gradingStatus = gradingStatus?.let(AnswerGradingStatus::valueOf),
                source = QuestionSource.MANUAL,
                publicQuestion = publicQuestion,
                deletedAt = deletedAt,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        )

    private suspend fun pendingPublicQuestion(user: UserEntity, topic: String, createdAt: Instant = now): QuestionEntity =
        questions.save(
            QuestionEntity(
                deviceId = "device-${user.id}",
                userId = user.id,
                question = "Pending question for $topic",
                topic = topic,
                difficultyLevel = 4,
                scheduledFor = createdAt,
                sentAt = createdAt,
                status = QuestionStatus.UNGRADED,
                source = QuestionSource.SCHEDULED,
                publicQuestion = true,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        )

    private suspend fun assertRecordNotFound(block: suspend () -> Unit) {
        assertThatThrownBy { runBlocking { block() } }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
    }
}
