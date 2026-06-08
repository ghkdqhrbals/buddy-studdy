package com.buddystuddy.backend.community.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.repository.UserRepository
import com.buddystuddy.backend.common.api.ApiErrorCode
import com.buddystuddy.backend.common.api.ApiException
import com.buddystuddy.backend.community.repository.QuestionCommentRepository
import com.buddystuddy.backend.community.repository.QuestionLikeRepository
import com.buddystuddy.backend.community.repository.ReportRepository
import com.buddystuddy.backend.domain.QuestionCommentEntity
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionLikeEntity
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.domain.ReportEntity
import com.buddystuddy.backend.dto.CommunityCommentRequest
import com.buddystuddy.backend.dto.CommunityCommentResponse
import com.buddystuddy.backend.dto.CommunityCommentsResponse
import com.buddystuddy.backend.dto.CommunityLikeResponse
import com.buddystuddy.backend.dto.CommunityQuestionResponse
import com.buddystuddy.backend.dto.CommunityQuestionsResponse
import com.buddystuddy.backend.dto.ReportQuestionRequest
import com.buddystuddy.backend.dto.UserProfileResponse
import com.buddystuddy.backend.dto.toCommunity
import com.buddystuddy.backend.dto.toProfile
import com.buddystuddy.backend.dto.toResponse
import com.buddystuddy.backend.stream.QuestionStreamEventType
import com.buddystuddy.backend.stream.RedisStreamCoordinatorService
import com.buddystuddy.backend.study.repository.QuestionRepository
import com.buddystuddy.backend.study.repository.QuestionStatsRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CommunityService(
    private val users: UserRepository,
    private val questions: QuestionRepository,
    private val questionStats: QuestionStatsRepository,
    private val likes: QuestionLikeRepository,
    private val comments: QuestionCommentRepository,
    private val reports: ReportRepository,
    private val streams: RedisStreamCoordinatorService,
) {
    @Transactional(readOnly = true)
    fun publicQuestions(principal: Principal?, topic: String?, limit: Int, offset: Int): CommunityQuestionsResponse {
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
    fun publicQuestion(principal: Principal?, id: Long): CommunityQuestionResponse {
        val q = publicAnsweredQuestion(id)
        streams.publishQuestionViewed(id, principal?.userId)
        return community(q, principal)
    }

    @Transactional
    fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse {
        publicAnsweredQuestion(id)
        var delta = 0
        if (liked) {
            if (!likes.existsByQuestionIdAndUserId(id, principal.userId)) {
                likes.save(QuestionLikeEntity(questionId = id, userId = principal.userId))
                delta = 1
                streams.publishQuestionChanged(id, QuestionStreamEventType.QUESTION_LIKED, principal.userId)
            }
        } else {
            if (likes.deleteByQuestionIdAndUserId(id, principal.userId) > 0) {
                delta = -1
                streams.publishQuestionChanged(id, QuestionStreamEventType.QUESTION_UNLIKED, principal.userId)
            }
        }
        val stats = questionStats.findById(id).orElse(QuestionStatsEntity(questionId = id))
        return CommunityLikeResponse(id.toString(), (stats.likeCount + delta).coerceAtLeast(0), liked)
    }

    @Transactional
    fun comment(principal: Principal, id: Long, body: String): CommunityCommentResponse {
        publicAnsweredQuestion(id)
        val saved = comments.save(QuestionCommentEntity(questionId = id, userId = principal.userId, body = body.take(1000)))
        streams.publishQuestionChanged(id, QuestionStreamEventType.QUESTION_COMMENTED, principal.userId)
        return saved.toResponse(userProfile(principal.userId))
    }

    @Transactional(readOnly = true)
    fun comments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse {
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
    fun report(principal: Principal, id: Long, payload: ReportQuestionRequest) {
        publicAnsweredQuestion(id)
        reports.save(
            ReportEntity(
                questionId = id,
                reporterDeviceId = principal.deviceId,
                reporterUserId = principal.userId,
                reason = payload.reason,
                message = payload.message,
            )
        )
    }

    private fun community(q: QuestionEntity, principal: Principal?): CommunityQuestionResponse {
        val author = q.userId?.let { users.findById(it).orElse(null)?.toProfile() }
        val stats = questionStats.findById(q.id).orElse(null)
        val liked = principal?.let { likes.existsByQuestionIdAndUserId(q.id, it.userId) } ?: false
        return q.toCommunity(author, stats, liked)
    }

    private fun publicAnsweredQuestion(id: Long): QuestionEntity =
        questions.findPublicAnsweredById(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")

    private fun userProfile(id: Long) = users.findById(id).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
    }.toProfile()
}
