package com.buddystudy.backend.community.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystudy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.community.application.port.outbound.ReportPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.QuestionSearchEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.backend.community.application.model.CommunityCommentResponse
import com.buddystudy.backend.community.application.model.CommunityCommentDeleteResponse
import com.buddystudy.backend.community.application.model.CommunityCommentsResponse
import com.buddystudy.backend.community.application.model.CommunityLikeResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionsResponse
import com.buddystudy.backend.community.application.model.toCommunityQuestionResponse
import com.buddystudy.community.domain.PublicQuestion
import com.buddystudy.community.domain.PublicQuestionAuthorProjection
import com.buddystudy.community.domain.PublicQuestionState
import com.buddystudy.community.domain.PublicQuestionStats
import com.buddystudy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.profile.application.model.toProfile
import com.buddystudy.backend.community.application.model.toResponse
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.account.domain.entity.UserEntity
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
    private val search: QuestionSearchPort,
    private val notifications: PublishNotificationUseCase,
) : CommunityUseCase {
    @Transactional(readOnly = true)
    override suspend fun getPublicQuestions(principal: Principal?, query: String?, language: String, limit: Int, offset: Int): CommunityQuestionsResponse {
        return getPublicQuestionsV2(principal, query, language, limit, offset)
    }

    @Transactional(readOnly = true)
    override suspend fun getPublicQuestionsV2(principal: Principal?, query: String?, language: String, limit: Int, offset: Int): CommunityQuestionsResponse {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val result = search.searchPublic(normalizedQuery, language, limit, offset)
        if (result.questionIds.isEmpty() && normalizedQuery != null) {
            return publicQuestionsFromOrigin(principal, normalizedQuery, language, limit, offset)
        }
        if (result.questionIds.isEmpty()) {
            return CommunityQuestionsResponse(emptyList(), result.totalCount, limit, offset)
        }
        val questionsById = questions.findPublicAnsweredByIds(result.questionIds).associateBy { it.id }
        val translatedById = translatedRows(result.questionIds, language)
        val orderedQuestions = result.questionIds.mapNotNull { questionsById[it] }
        val context = communityContext(orderedQuestions, principal)
        val rows = orderedQuestions.map { community(it, context, translatedById[it.id]) }
        return CommunityQuestionsResponse(rows, result.totalCount, limit, offset)
    }

    private suspend fun publicQuestionsFromOrigin(
        principal: Principal?,
        query: String,
        language: String,
        limit: Int,
        offset: Int,
    ): CommunityQuestionsResponse {
        val page = questions.findPublicAnsweredByQuery(query, PageRequest.of(offset / limit, limit))
        val questionIds = page.content.map { it.id }
        val translatedById = translatedRows(questionIds, language)
        val context = communityContext(page.content, principal)
        val rows = page.content.map { community(it, context, translatedById[it.id]) }
        return CommunityQuestionsResponse(rows, page.totalElements, limit, offset)
    }

    @Transactional
    override suspend fun getPublicQuestion(principal: Principal?, id: Long, language: String): CommunityQuestionResponse {
        val q = publicAnsweredQuestion(id)
        reactions.publishViewed(id, principal?.userId)
        return community(q, communityContext(listOf(q), principal), search.findPublicByQuestionIdAndLanguage(id, language))
    }

    @Transactional
    override suspend fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse {
        val question = publicAnsweredQuestion(id)
        var changed = false
        if (liked) {
            if (!likes.existsByQuestionIdAndUserId(id, principal.userId)) {
                likes.save(QuestionLikeEntity(questionId = id, userId = principal.userId))
                changed = true
            }
        } else {
            changed = likes.deleteByQuestionIdAndUserId(id, principal.userId) > 0
        }
        val likeCount = if (changed) {
            incrementLikeCount(id, if (liked) 1 else -1)
        } else {
            currentLikeCount(id)
        }
        if (changed && liked) {
            publishThreadNotification(
                ownerUserId = question.userId,
                actorUserId = principal.userId,
                eventId = "question-like-$id-${principal.userId}",
                title = "새 좋아요",
                body = "내 질문에 좋아요가 추가되었습니다.",
                questionId = id,
                shouldPush = false,
            )
        }
        return CommunityLikeResponse(id.toString(), likeCount, liked)
    }

    @Transactional
    override suspend fun createComment(principal: Principal, id: Long, body: String): CommunityCommentResponse {
        val question = publicAnsweredQuestion(id)
        val saved = comments.save(QuestionCommentEntity(questionId = id, userId = principal.userId, body = body.take(1000)))
        incrementCommentCount(id, 1)
        publishThreadNotification(
            ownerUserId = question.userId,
            actorUserId = principal.userId,
            eventId = "question-comment-${saved.id}",
            title = "새 댓글",
            body = saved.body,
            questionId = id,
            shouldPush = true,
        )
        return saved.toResponse(userProfile(principal.userId))
    }

    @Transactional
    override suspend fun deleteComment(principal: Principal, id: Long, commentId: Long): CommunityCommentDeleteResponse {
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
        incrementCommentCount(id, -1)
        return CommunityCommentDeleteResponse(comment.id.toString(), id.toString())
    }

    @Transactional(readOnly = true)
    override suspend fun getComments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse {
        publicAnsweredQuestion(id)
        val page = comments.findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(id, PageRequest.of(offset / limit, limit))
        val profiles = page.content
            .map { it.userId }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { users.findAllById(it).associateBy { user -> user.id } }
            .orEmpty()
        return CommunityCommentsResponse(
            page.content.map { it.toResponse(profiles[it.userId]?.toProfile() ?: UserProfileResponse(0, "Buddy")) },
            page.totalElements,
            limit,
            offset,
        )
    }

    @Transactional
    override suspend fun reportQuestion(principal: Principal, id: Long, command: ReportQuestionCommand) {
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

    private suspend fun communityContext(questions: List<QuestionEntity>, principal: Principal?): CommunityContext {
        if (questions.isEmpty()) return CommunityContext()
        val userIds = questions.mapNotNull { it.userId }.distinct()
        val questionIds = questions.map { it.id }
        return CommunityContext(
            authorsById = users.findAllById(userIds).associateBy { it.id },
            statsByQuestionId = questionStats.findAllByIds(questionIds).associateBy { it.questionId },
            likedQuestionIds = principal?.let { likes.findLikedQuestionIds(it.userId, questionIds) }.orEmpty(),
        )
    }

    private suspend fun community(q: QuestionEntity, context: CommunityContext, translated: QuestionSearchEntity? = null): CommunityQuestionResponse {
        val author = q.userId?.let { context.authorsById[it]?.toAuthorProjection() }
        val stats = context.statsByQuestionId[q.id]
        val liked = q.id in context.likedQuestionIds
        return PublicQuestion.of(q.toPublicQuestionState(), author, stats?.toPublicQuestionStats(), liked)
            .toProjection()
            .toCommunityQuestionResponse()
            .withTranslatedText(translated)
    }

    private suspend fun publicAnsweredQuestion(id: Long): QuestionEntity =
        questions.findPublicAnsweredById(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")

    private suspend fun incrementLikeCount(questionId: Long, delta: Int): Int {
        val now = Instant.now()
        if (questionStats.incrementLike(questionId, delta, now) == 0) {
            return questionStats.save(
                QuestionStatsEntity(
                    questionId = questionId,
                    likeCount = maxOf(0, delta),
                    updatedAt = now,
                )
            ).likeCount
        }
        return currentLikeCount(questionId)
    }

    private suspend fun currentLikeCount(questionId: Long): Int =
        questionStats.findById(questionId)?.likeCount ?: 0

    private suspend fun incrementCommentCount(questionId: Long, delta: Int) {
        val now = Instant.now()
        if (questionStats.incrementComment(questionId, delta, now) == 0) {
            questionStats.save(
                QuestionStatsEntity(
                    questionId = questionId,
                    commentCount = maxOf(0, delta),
                    updatedAt = now,
                )
            )
        }
    }

    private suspend fun userProfile(id: Long) = (
        users.findById(id)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
        ).toProfile()

    private suspend fun translatedRows(questionIds: Collection<Long>, language: String): Map<Long, QuestionSearchEntity> =
        questionIds.mapNotNull { id ->
            search.findPublicByQuestionIdAndLanguage(id, language)?.let { id to it }
        }.toMap()

    private suspend fun publishThreadNotification(
        ownerUserId: Long?,
        actorUserId: Long,
        eventId: String,
        title: String,
        body: String,
        questionId: Long,
        shouldPush: Boolean,
    ) {
        val recipientId = ownerUserId ?: return
        if (recipientId == actorUserId) return
        notifications.publish(
            NotificationRequestCommand(
                eventId = eventId,
                userId = recipientId,
                actorUserId = actorUserId,
                type = "THREAD_ACTIVITY",
                title = title,
                body = body,
                threadType = "question",
                threadId = questionId.toString(),
                deepLink = "buddystudy://public/questions/$questionId",
                shouldPush = shouldPush,
            )
        )
    }
}

private suspend fun CommunityQuestionResponse.withTranslatedText(translated: QuestionSearchEntity?): CommunityQuestionResponse {
    if (translated == null) return this
    return copy(
        question = translated.question,
        answer = translated.answer,
        gradingResult = gradingResult?.copy(
            feedback = translated.feedback ?: gradingResult.feedback,
            explanation = translated.explanation ?: gradingResult.explanation,
        ),
    )
}

private data class CommunityContext(
    val authorsById: Map<Long, UserEntity> = emptyMap(),
    val statsByQuestionId: Map<Long, QuestionStatsEntity> = emptyMap(),
    val likedQuestionIds: Set<Long> = emptySet(),
)

private suspend fun UserEntity.toAuthorProjection() = PublicQuestionAuthorProjection(
    id = id,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    publicQuestionsAllowed = allowPublicQuestions,
)

private suspend fun QuestionEntity.toPublicQuestionState() = PublicQuestionState(
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

private suspend fun QuestionStatsEntity.toPublicQuestionStats() = PublicQuestionStats(
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
)
