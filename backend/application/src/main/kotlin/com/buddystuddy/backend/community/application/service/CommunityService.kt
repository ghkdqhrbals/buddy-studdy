package com.buddystuddy.backend.community.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystuddy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystuddy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystuddy.backend.community.application.port.outbound.ReportPort
import com.buddystuddy.community.domain.entity.QuestionCommentEntity
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.community.domain.entity.QuestionLikeEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.community.domain.entity.ReportEntity
import com.buddystuddy.backend.community.application.model.CommunityCommentResponse
import com.buddystuddy.backend.community.application.model.CommunityCommentDeleteResponse
import com.buddystuddy.backend.community.application.model.CommunityCommentsResponse
import com.buddystuddy.backend.community.application.model.CommunityLikeResponse
import com.buddystuddy.backend.community.application.model.CommunityQuestionResponse
import com.buddystuddy.backend.community.application.model.CommunityQuestionsResponse
import com.buddystuddy.backend.community.application.model.toCommunityQuestionResponse
import com.buddystuddy.community.domain.PublicQuestion
import com.buddystuddy.community.domain.PublicQuestionAuthorProjection
import com.buddystuddy.community.domain.PublicQuestionState
import com.buddystuddy.community.domain.PublicQuestionStats
import com.buddystuddy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystuddy.backend.profile.application.model.UserProfileResponse
import com.buddystuddy.backend.profile.application.model.toProfile
import com.buddystuddy.backend.community.application.model.toResponse
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.account.domain.entity.UserEntity
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class CommunityService(
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val likes: QuestionLikePort,
    private val comments: QuestionCommentPort,
    private val reports: ReportPort,
    private val reactions: PublicQuestionReactionPublishPort,
) : CommunityUseCase {
    @Transactional(readOnly = true)
    override fun publicQuestions(principal: Principal?, topic: String?, limit: Int, offset: Int): CommunityQuestionsResponse {
        val pageable = PageRequest.of(offset / limit, limit)
        val normalizedTopic = topic?.takeIf { it.isNotBlank() }
        val page = if (normalizedTopic == null) {
            questions.findPublicAnswered(pageable)
        } else {
            questions.findPublicAnsweredByTopic(normalizedTopic, pageable)
        }
        val rows = page.content.map { community(it, principal) }
        return CommunityQuestionsResponse(rows, page.totalElements, limit, offset)
    }

    @Transactional
    override fun publicQuestion(principal: Principal?, id: Long): CommunityQuestionResponse {
        val q = publicAnsweredQuestion(id)
        reactions.publishViewed(id, principal?.userId)
        return community(q, principal)
    }

    @Transactional
    override fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse {
        publicAnsweredQuestion(id)
        if (liked) {
            if (!likes.existsByQuestionIdAndUserId(id, principal.userId)) {
                likes.save(QuestionLikeEntity(questionId = id, userId = principal.userId))
                reactions.publishLiked(id, principal.userId)
            }
        } else {
            if (likes.deleteByQuestionIdAndUserId(id, principal.userId) > 0) {
                reactions.publishUnliked(id, principal.userId)
            }
        }
        val likeCount = likes.countByQuestionId(id).toInt()
        overwriteLikeCount(id, likeCount)
        return CommunityLikeResponse(id.toString(), likeCount, liked)
    }

    @Transactional
    override fun comment(principal: Principal, id: Long, body: String): CommunityCommentResponse {
        publicAnsweredQuestion(id)
        val saved = comments.save(QuestionCommentEntity(questionId = id, userId = principal.userId, body = body.take(1000)))
        reactions.publishCommented(id, principal.userId)
        return saved.toResponse(userProfile(principal.userId))
    }

    @Transactional
    override fun deleteComment(principal: Principal, id: Long, commentId: Long): CommunityCommentDeleteResponse {
        publicAnsweredQuestion(id)
        val comment = comments.findByIdAndQuestionIdAndDeletedAtIsNull(commentId, id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Comment not found.")
        if (comment.userId != principal.userId) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Comment not found.")
        }

        val now = Instant.now()
        comment.deletedAt = now
        comment.updatedAt = now
        comments.save(comment)
        reactions.publishCommentDeleted(id, principal.userId)
        return CommunityCommentDeleteResponse(comment.id.toString(), id.toString())
    }

    @Transactional(readOnly = true)
    override fun comments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse {
        publicAnsweredQuestion(id)
        val page = comments.findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtDesc(id, PageRequest.of(offset / limit, limit))
        val profiles = users.findAllById(page.content.map { it.userId }).associateBy { it.id }
        return CommunityCommentsResponse(
            page.content.map { it.toResponse(profiles[it.userId]?.toProfile() ?: UserProfileResponse(0, "Buddy")) },
            page.totalElements,
            limit,
            offset,
        )
    }

    @Transactional
    override fun report(principal: Principal, id: Long, command: ReportQuestionCommand) {
        publicAnsweredQuestion(id)
        reports.save(
            ReportEntity(
                questionId = id,
                reporterDeviceId = principal.deviceId,
                reporterUserId = principal.userId,
                reason = command.reason,
                message = command.message,
            )
        )
    }

    private fun community(q: QuestionEntity, principal: Principal?): CommunityQuestionResponse {
        val author = q.userId?.let { users.findById(it).orElse(null)?.toAuthorProjection() }
        val stats = questionStats.findById(q.id).orElse(null)
        val liked = principal?.let { likes.existsByQuestionIdAndUserId(q.id, it.userId) } ?: false
        return PublicQuestion.of(q.toPublicQuestionState(), author, stats?.toPublicQuestionStats(), liked).toProjection().toCommunityQuestionResponse()
    }

    private fun publicAnsweredQuestion(id: Long): QuestionEntity =
        questions.findPublicAnsweredById(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")

    private fun overwriteLikeCount(questionId: Long, likeCount: Int) {
        val now = Instant.now()
        if (questionStats.setLikeCount(questionId, likeCount, now) == 0) {
            questionStats.save(QuestionStatsEntity(questionId = questionId, likeCount = likeCount, updatedAt = now))
        }
    }

    private fun userProfile(id: Long) = users.findById(id).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
    }.toProfile()
}

private fun UserEntity.toAuthorProjection() = PublicQuestionAuthorProjection(
    id = id,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    publicQuestionsAllowed = allowPublicQuestions,
)

private fun QuestionEntity.toPublicQuestionState() = PublicQuestionState(
    id = id,
    question = question,
    answer = answer,
    score = score,
    correct = correct,
    feedback = feedback,
    explanation = explanation,
    topic = topic,
    difficultyLevel = difficultyLevel,
    status = status,
    source = source,
    createdAt = createdAt,
    answeredAt = answeredAt,
)

private fun QuestionStatsEntity.toPublicQuestionStats() = PublicQuestionStats(
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
)
