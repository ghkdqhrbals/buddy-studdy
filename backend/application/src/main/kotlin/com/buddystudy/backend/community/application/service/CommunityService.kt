package com.buddystudy.backend.community.application.service

import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.community.application.port.outbound.FeedbackPort
import com.buddystudy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.community.application.port.outbound.ReportPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.ContentTranslationRequestAppendPort
import com.buddystudy.backend.localization.application.port.RequestContentLocalizationUseCase
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.backend.community.application.model.FeedbackResponse
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
import com.buddystudy.backend.community.application.port.inbound.SubmitFeedbackCommand
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
import java.util.UUID

@Service
class CommunityService(
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val likes: QuestionLikePort,
    private val comments: QuestionCommentPort,
    private val reports: ReportPort,
    private val feedbacks: FeedbackPort,
    private val reactions: PublicQuestionReactionPublishPort,
    private val notifications: PublishNotificationUseCase,
    private val languageDetector: ContentLanguageDetectionPort,
    private val contentLocalizations: ContentLocalizationPort,
    private val localizationRequests: RequestContentLocalizationUseCase,
    private val translationRequestManager: ContentTranslationRequestAppendPort,
    private val afterCommit: AfterCommitPort,
    private val outboxPublisher: PublishOutboxUseCase,
) : CommunityUseCase {
    @Transactional(readOnly = true)
    override suspend fun getPublicQuestions(
        principal: Principal?,
        query: String?,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
    ): CommunityQuestionsResponse {
        return getPublicQuestionsV2(principal, query, language, view, limit, offset)
    }

    @Transactional(readOnly = true)
    override suspend fun getPublicQuestionsV2(
        principal: Principal?,
        query: String?,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
    ): CommunityQuestionsResponse {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        return publicQuestionsFromOrigin(
            principal = principal,
            query = normalizedQuery,
            language = QuestionLanguage.normalize(language),
            view = view,
            limit = limit,
            offset = offset,
        )
    }

    private suspend fun publicQuestionsFromOrigin(
        principal: Principal?,
        query: String?,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
    ): CommunityQuestionsResponse {
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (query == null) {
            questions.findPublicAnswered(pageable)
        } else {
            questions.findPublicAnsweredByLanguageAndQuery(language, query, pageable)
        }
        val context = communityContext(page.content, principal)
        val viewMode = translationViewMode(view)
        val rows = page.content.map { community(it, context, language, viewMode) }
        return CommunityQuestionsResponse(rows, page.totalElements, limit, offset)
    }

    override suspend fun getPublicQuestion(
        principal: Principal?,
        id: Long,
        language: String,
        view: String,
    ): CommunityQuestionResponse {
        val q = publicAnsweredQuestion(id)
        val viewMode = translationViewMode(view)
        val response = community(
            q,
            communityContext(listOf(q), principal),
            language,
            viewMode,
        )
        response.localization?.let { localization ->
            reactions.publishViewed(
                id,
                principal?.userId,
                PublicQuestionViewLocalization(
                    translationState = localization.question.translationState.name,
                    translationLanguage = localization.question.requestedLanguage,
                    translationReason = localization.question.translationReason.name,
                    requestId = UUID.randomUUID().toString(),
                    questionSourceLanguage = localization.question.sourceLanguage,
                    questionDisplayLanguage = localization.question.displayLanguage,
                    answerSourceLanguage = localization.answer?.sourceLanguage,
                    answerDisplayLanguage = localization.answer?.displayLanguage,
                    aiResponseSourceLanguage = localization.aiResponse?.sourceLanguage,
                    aiResponseDisplayLanguage = localization.aiResponse?.displayLanguage,
                ),
            )
        } ?: reactions.publishViewed(id, principal?.userId)
        return response
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
        if (changed) {
            if (liked) {
                reactions.publishLiked(id, principal.userId)
            } else {
                reactions.publishUnliked(id, principal.userId)
            }
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
    override suspend fun createComment(
        principal: Principal,
        id: Long,
        body: String,
        sourceLanguage: String?,
    ): CommunityCommentResponse {
        val question = publicAnsweredQuestion(id)
        val fallbackLanguage = users.findById(principal.userId)?.appLanguage?.databaseValue
        val declaredLanguage = QuestionLanguage.normalize(sourceLanguage ?: fallbackLanguage)
        val now = Instant.now()
        val saved = comments.save(
            QuestionCommentEntity(
                questionId = id,
                userId = principal.userId,
                body = body.take(1000),
                sourceLanguage = SupportedLanguage.fromLocale(languageDetector.detect(body, declaredLanguage)),
            ),
        )
        val translationOutboxes = translationRequestManager.appendCommentForSupportedLanguages(saved, now)
        if (translationOutboxes.isNotEmpty()) {
            afterCommit.execute { outboxPublisher.publishNow(translationOutboxes) }
        }
        incrementCommentCount(id, 1)
        reactions.publishCommented(id, saved.id, principal.userId)
        publishThreadNotification(
            ownerUserId = question.userId,
            actorUserId = principal.userId,
            eventId = "question-comment-${saved.id}",
            title = localizedCommentTitle(question.userId),
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
        reactions.publishCommentDeleted(id, comment.id, principal.userId)
        return CommunityCommentDeleteResponse(comment.id.toString(), id.toString())
    }

    @Transactional(readOnly = true)
    override suspend fun getComments(
        id: Long,
        language: String,
        view: String,
        limit: Int,
        offset: Int,
        principal: Principal?,
    ): CommunityCommentsResponse {
        publicAnsweredQuestion(id)
        val requestedLanguage = QuestionLanguage.normalize(language)
        val viewMode = if (view.equals("original", ignoreCase = true)) {
            TranslationViewMode.ORIGINAL
        } else {
            TranslationViewMode.LOCALIZED
        }
        val page = comments.findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(id, PageRequest.of(offset / limit, limit))
        val profiles = page.content
            .map { it.userId }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { users.findAllById(it).associateBy { user -> user.id } }
            .orEmpty()
        return CommunityCommentsResponse(
            page.content.map { comment ->
                val authorOriginal = comment.userId == principal?.userId
                val projected = localizedComment(comment, requestedLanguage, viewMode, authorOriginal)
                projected.comment.toResponse(
                    profiles[comment.userId]?.toProfile() ?: UserProfileResponse(0, "Buddy"),
                    requestedLanguage,
                    viewMode,
                    projected.displayLanguage,
                    projected.translationPending,
                    authorOriginal,
                )
            },
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

    @Transactional
    override suspend fun submitFeedback(
        principal: Principal?,
        deviceId: String?,
        command: SubmitFeedbackCommand,
    ): FeedbackResponse {
        val normalizedContent = command.content.trim()
        if (normalizedContent.length !in 2..1000) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Feedback must contain between 2 and 1000 characters.",
            )
        }
        val saved = feedbacks.save(
            FeedbackEntity(
                userId = principal?.userId,
                deviceId = principal?.deviceId ?: deviceId?.trim()?.takeIf { it.isNotEmpty() },
                content = normalizedContent,
            ),
        )
        return FeedbackResponse(saved.id, saved.createdAt)
    }

    private suspend fun communityContext(questions: List<QuestionEntity>, principal: Principal?): CommunityContext {
        if (questions.isEmpty()) return CommunityContext()
        val userIds = questions.mapNotNull { it.userId }.distinct()
        val questionIds = questions.map { it.id }
        return CommunityContext(
            authorsById = users.findAllById(userIds).associateBy { it.id },
            statsByQuestionId = questionStats.findAllByIds(questionIds).associateBy { it.questionId },
            likedQuestionIds = principal?.let { likes.findLikedQuestionIds(it.userId, questionIds) }.orEmpty(),
            viewerUserId = principal?.userId,
        )
    }

    private suspend fun community(
        q: QuestionEntity,
        context: CommunityContext,
        requestedLanguage: String = q.sourceLanguage.databaseValue,
        viewMode: TranslationViewMode = TranslationViewMode.LOCALIZED,
    ): CommunityQuestionResponse {
        val projected = localizedRecord(
            q,
            requestedLanguage,
            viewMode,
            preserveAnswerOriginal = q.userId != null && q.userId == context.viewerUserId,
        )
        val displayQuestion = projected.question
        val author = displayQuestion.userId?.let { context.authorsById[it]?.toAuthorProjection() }
        val stats = context.statsByQuestionId[displayQuestion.id]
        val liked = displayQuestion.id in context.likedQuestionIds
        return PublicQuestion.of(displayQuestion.toPublicQuestionState(), author, stats?.toPublicQuestionStats(), liked)
            .toProjection()
            .toCommunityQuestionResponse(
                requestedLanguage = QuestionLanguage.normalize(requestedLanguage),
                viewMode = viewMode,
                questionDisplayLanguage = projected.questionDisplayLanguage,
                answerDisplayLanguage = projected.answerDisplayLanguage,
                aiResponseDisplayLanguage = projected.aiResponseDisplayLanguage,
                questionTranslationPending = projected.questionTranslationPending,
                answerTranslationPending = projected.answerTranslationPending,
                aiResponseTranslationPending = projected.aiResponseTranslationPending,
                answerAuthorOriginal = projected.answerAuthorOriginal,
            )
    }

    private suspend fun localizedRecord(
        question: QuestionEntity,
        requestedLanguage: String,
        viewMode: TranslationViewMode,
        preserveAnswerOriginal: Boolean,
    ): ProjectedRecord {
        val target = QuestionLanguage.normalize(requestedLanguage)
        val questionSource = QuestionLanguage.normalize(question.sourceLanguage.databaseValue)
        val answerSource = QuestionLanguage.normalize(
            (question.answerSourceLanguage ?: question.sourceLanguage).databaseValue,
        )
        val aiSource = QuestionLanguage.normalize(
            (question.aiResponseSourceLanguage ?: question.sourceLanguage).databaseValue,
        )
        if (viewMode == TranslationViewMode.ORIGINAL) {
            return ProjectedRecord(
                question,
                questionSource,
                answerSource,
                aiSource,
                false,
                false,
                false,
                preserveAnswerOriginal && !question.answer.isNullOrBlank(),
            )
        }

        val hashes = ContentSourceHashPolicy.recordHashes(question)
        val snapshot = contentLocalizations.record(question.id, target)
        val questionReady = snapshot.question.readyFor(hashes.question)
        val answerReady = snapshot.answer.readyFor(hashes.answer)
        val aiReady = snapshot.aiResponse.readyFor(hashes.aiResponse)
        val needsTranslation =
            (questionSource != target && questionReady == null) ||
                (!preserveAnswerOriginal && !question.answer.isNullOrBlank() &&
                    answerSource != target && answerReady == null) ||
                ((!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) &&
                    aiSource != target && aiReady == null)
        if (needsTranslation) {
            localizationRequests.requestRecord(question, target)
        }

        var questionDisplay = questionSource
        var answerDisplay = answerSource
        var aiDisplay = aiSource
        if (questionSource != target) {
            if (questionReady != null) {
                question.topic = questionReady.fields["topic"] ?: question.topic
                question.question = questionReady.fields["question"] ?: question.question
                question.hint = questionReady.fields["hint"] ?: question.hint
                questionDisplay = target
            }
        }
        if (!preserveAnswerOriginal && !question.answer.isNullOrBlank() && answerSource != target) {
            answerReady?.let {
                question.answer = it.fields["answer"] ?: question.answer
                answerDisplay = target
            }
        }
        if ((!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) && aiSource != target) {
            aiReady?.let {
                question.feedback = it.fields["feedback"] ?: question.feedback
                question.explanation = it.fields["explanation"] ?: question.explanation
                aiDisplay = target
            }
        }
        return ProjectedRecord(
            question,
            questionDisplay,
            answerDisplay,
            aiDisplay,
            questionSource != target && questionDisplay != target && snapshot.question?.status != "FAILED",
            !preserveAnswerOriginal && !question.answer.isNullOrBlank() && answerSource != target &&
                answerDisplay != target && snapshot.answer?.status != "FAILED",
            (!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) &&
                aiSource != target && aiDisplay != target && snapshot.aiResponse?.status != "FAILED",
            preserveAnswerOriginal && !question.answer.isNullOrBlank(),
        )
    }

    private suspend fun localizedComment(
        comment: QuestionCommentEntity,
        requestedLanguage: String,
        viewMode: TranslationViewMode,
        authorOriginal: Boolean,
    ): ProjectedComment {
        val target = QuestionLanguage.normalize(requestedLanguage)
        val source = QuestionLanguage.normalize(comment.sourceLanguage.databaseValue)
        if (authorOriginal || viewMode == TranslationViewMode.ORIGINAL || source == target) {
            return ProjectedComment(comment, source, false)
        }
        val sourceHash = ContentSourceHashPolicy.sha256(comment.body)
        val snapshot = contentLocalizations.comment(comment.id, target)
        val ready = snapshot.readyFor(sourceHash)
        if (ready == null) {
            localizationRequests.requestComment(comment, target)
            val failed = snapshot?.status == "FAILED"
            return ProjectedComment(comment, source, !failed)
        }
        comment.body = ready.fields["body"] ?: comment.body
        return ProjectedComment(comment, target, false)
    }

    private fun TextLocalizationSnapshot?.readyFor(sourceHash: String?) =
        sourceHash?.let { hash -> this?.takeIf { it.status == "READY" && it.sourceHash == hash } }

    private suspend fun publicAnsweredQuestion(id: Long, language: String? = null): QuestionEntity =
        (if (language == null) {
            questions.findPublicAnsweredById(id)
        } else {
            questions.findPublicAnsweredByIdAndLanguage(id, language)
        })
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

    private suspend fun localizedCommentTitle(ownerUserId: Long?): String {
        return when (
            QuestionLanguage.normalize(ownerUserId?.let { users.findById(it)?.appLanguage?.databaseValue })
        ) {
            QuestionLanguage.ENGLISH -> "Comment"
            QuestionLanguage.JAPANESE -> "コメント"
            else -> "댓글"
        }
    }

    private fun translationViewMode(value: String): TranslationViewMode =
        if (value.equals("original", ignoreCase = true)) {
            TranslationViewMode.ORIGINAL
        } else {
            TranslationViewMode.LOCALIZED
        }

    private data class ProjectedRecord(
        val question: QuestionEntity,
        val questionDisplayLanguage: String,
        val answerDisplayLanguage: String,
        val aiResponseDisplayLanguage: String,
        val questionTranslationPending: Boolean,
        val answerTranslationPending: Boolean,
        val aiResponseTranslationPending: Boolean,
        val answerAuthorOriginal: Boolean,
    )

    private data class ProjectedComment(
        val comment: QuestionCommentEntity,
        val displayLanguage: String,
        val translationPending: Boolean,
    )
}

private data class CommunityContext(
    val authorsById: Map<Long, UserEntity> = emptyMap(),
    val statsByQuestionId: Map<Long, QuestionStatsEntity> = emptyMap(),
    val likedQuestionIds: Set<Long> = emptySet(),
    val viewerUserId: Long? = null,
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
    status = status.databaseValue,
    source = source.databaseValue,
    createdAt = createdAt,
    answeredAt = answeredAt,
    questionSourceLanguage = sourceLanguage.databaseValue,
    answerSourceLanguage = answerSourceLanguage?.databaseValue,
    aiResponseSourceLanguage = aiResponseSourceLanguage?.databaseValue,
)

private suspend fun QuestionStatsEntity.toPublicQuestionStats() = PublicQuestionStats(
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
)
