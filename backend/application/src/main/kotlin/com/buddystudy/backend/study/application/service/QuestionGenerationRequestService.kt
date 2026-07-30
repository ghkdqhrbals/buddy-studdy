package com.buddystudy.backend.study.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.OutboxType
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.QueuedQuestionGeneration
import com.buddystudy.backend.study.application.model.QuestionGenerationAcceptedResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationErrorResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationProcessResponse
import com.buddystudy.backend.study.application.model.QuestionGenerationRequestedEvent
import com.buddystudy.backend.study.application.model.QuestionGenerationSaga
import com.buddystudy.backend.study.application.model.QuestionGenerationSource
import com.buddystudy.backend.study.application.model.QuestionGenerationStatus
import com.buddystudy.backend.study.application.model.QuestionGenerationStep
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.port.inbound.GetQuestionGenerationProcessUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionGenerationRequestWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.RequestQuestionGenerationUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionGenerationSagaPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.service.applyReadyQuestionLocalization
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.StudyRoom
import com.buddystudy.study.domain.StudyRoomPendingLimitExceeded
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class QuestionGenerationRequestService(
    private val writer: QuestionGenerationRequestWriteUseCase,
    private val publisher: PublishOutboxUseCase,
) : RequestQuestionGenerationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequirePermission(Permissions.QUESTION_CREATE)
    override suspend fun request(
        principal: Principal,
        studyId: Long,
        idempotencyKey: String,
    ): QuestionGenerationAcceptedResponse {
        val queued = writer.enqueueManual(
            userId = principal.userId,
            deviceId = principal.deviceId,
            studyId = studyId,
            idempotencyKey = idempotencyKey,
            now = Instant.now(),
        )
        runCatching { publisher.publishNow(queued.outboxes) }
            .onFailure {
                log.warn(
                    "question_generation_immediate_publish_failed correlationId={} error={}",
                    queued.accepted.correlationId,
                    it.message,
                )
            }
        return queued.accepted
    }
}

@Service
class QuestionGenerationRequestWriteService(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val users: UserPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val sagas: QuestionGenerationSagaPort,
    private val outbox: RedisEventOutboxAppendPort,
) : QuestionGenerationRequestWriteUseCase {
    @Transactional
    override suspend fun enqueueManual(
        userId: Long,
        deviceId: String,
        studyId: Long,
        idempotencyKey: String,
        now: Instant,
    ): QueuedQuestionGeneration {
        val scopedIdempotencyKey = manualIdempotencyKey(idempotencyKey)
        existing(userId, scopedIdempotencyKey)?.let { return it }
        val requestedStudy = studies.findByIdAndUserId(studyId, userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val user = users.findById(userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
        val userStudies = studies.findAllByUserId(userId)
        val rootStudy = StudyTreeSelector.rootFor(requestedStudy, userStudies)
        ensureQuestionCanBeCreated(requestedStudy, rootStudy, user)
        ensureNoActiveGeneration(userId, requestedStudy.id)

        return enqueue(
            user = user,
            rootStudy = rootStudy,
            topicStudy = requestedStudy,
            source = QuestionGenerationSource.MANUAL,
            idempotencyKey = scopedIdempotencyKey,
            now = now,
        )
    }

    @Transactional
    override suspend fun enqueueScheduled(
        scheduleStudy: com.buddystudy.study.domain.entity.StudyEntity,
        topicStudy: com.buddystudy.study.domain.entity.StudyEntity,
        idempotencyKey: String,
        now: Instant,
    ): QueuedQuestionGeneration {
        existing(scheduleStudy.userId, idempotencyKey)?.let { return it }
        val user = users.findById(scheduleStudy.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
        ensureQuestionCanBeCreated(topicStudy, scheduleStudy, user)
        ensureNoActiveGeneration(scheduleStudy.userId, topicStudy.id)
        val queued = enqueue(
            user = user,
            rootStudy = scheduleStudy,
            topicStudy = topicStudy,
            source = QuestionGenerationSource.SCHEDULED,
            idempotencyKey = idempotencyKey,
            now = now,
        )
        scheduleStudy.advanceScheduledRotation(topicStudy, now)
        if (scheduleStudy.id != topicStudy.id) {
            studies.save(topicStudy)
        }
        studies.save(scheduleStudy)
        return queued
    }

    private suspend fun ensureQuestionCanBeCreated(
        topicStudy: com.buddystudy.study.domain.entity.StudyEntity,
        rootStudy: com.buddystudy.study.domain.entity.StudyEntity,
        user: UserEntity,
    ) {
        val appLanguage = QuestionLanguage.normalize(user.appLanguage)
        val room = StudyRoom.of(
            topicStudy.toStudyRoomSchedule(
                appLanguage = appLanguage,
                questionStudyId = topicStudy.id,
                questionSettings = rootStudy,
            ),
            questions.countPendingForStudyAndLanguage(topicStudy.id, appLanguage),
        )
        try {
            room.canCreateQuestion(properties.scheduler.maxPendingPerStudy)
        } catch (_: StudyRoomPendingLimitExceeded) {
            throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS,
                "A pending question already exists for this study.",
            )
        }
    }

    private suspend fun enqueue(
        user: UserEntity,
        rootStudy: com.buddystudy.study.domain.entity.StudyEntity,
        topicStudy: com.buddystudy.study.domain.entity.StudyEntity,
        source: QuestionGenerationSource,
        idempotencyKey: String,
        now: Instant,
    ): QueuedQuestionGeneration {
        val questionKey = questionKeys.resolveForQuestionGeneration(user)
        val reservation = checkNotNull(questionKey.quotaReservation) {
            "Question quota reservation is required."
        }
        val correlationId = UUID.randomUUID().toString()
        val saga = QuestionGenerationSaga(
            correlationId = correlationId,
            userId = user.id,
            studyId = rootStudy.id,
            topicId = topicStudy.id,
            questionId = null,
            source = source,
            status = QuestionGenerationStatus.QUEUED,
            currentStep = QuestionGenerationStep.QUEUED,
            idempotencyKey = idempotencyKey,
            quotaPeriodStartedAt = reservation.periodStartedAt,
            quotaRefundedAt = null,
            failedStep = null,
            errorCode = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        if (!sagas.insert(saga)) {
            questionKeys.releaseQuestionReservation(questionKey, now)
            existing(user.id, idempotencyKey)?.let { return it }
            ensureNoActiveGeneration(user.id, topicStudy.id)
            error("Question generation request could not be persisted.")
        }
        val event = QuestionGenerationRequestedEvent(
            eventId = UUID.randomUUID().toString(),
            correlationId = correlationId,
            userId = user.id,
            studyId = rootStudy.id,
            topicId = topicStudy.id,
            source = source,
            occurredAt = now,
        )
        val outboxId = outbox.appendQuestionGenerationRequested(event, now)
        return saga.toQueued(OutboxReference(OutboxType.DOMAIN_EVENT, outboxId))
    }

    private suspend fun existing(userId: Long, idempotencyKey: String): QueuedQuestionGeneration? =
        sagas.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.toQueued()

    private suspend fun ensureNoActiveGeneration(userId: Long, topicId: Long) {
        if (sagas.findActiveByUserIdAndTopicId(userId, topicId) != null) {
            throw ApiException(
                HttpStatus.CONFLICT,
                ApiErrorCode.STUDY_PENDING_QUESTION_EXISTS,
                "A question is already being generated for this study.",
            )
        }
    }

    private fun manualIdempotencyKey(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > 100) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Idempotency-Key must contain between 1 and 100 characters.",
            )
        }
        return "manual:$normalized"
    }

    private fun QuestionGenerationSaga.toQueued(outbox: OutboxReference? = null) =
        QueuedQuestionGeneration(
            accepted = QuestionGenerationAcceptedResponse(
                correlationId = correlationId,
                studyId = studyId.toString(),
                topicId = topicId.toString(),
                status = status,
                submittedAt = createdAt,
            ),
            outboxes = listOfNotNull(outbox),
        )
}

internal fun com.buddystudy.study.domain.entity.StudyEntity.advanceScheduledRotation(
    topicStudy: com.buddystudy.study.domain.entity.StudyEntity,
    now: Instant,
) {
    nextDueAt = now.plusSeconds(intervalMinutes.toLong() * 60)
    scheduleClaimedUntil = null
    lastError = null
    updatedAt = now
    topicStudy.lastSentAt = now
    topicStudy.lastError = null
    topicStudy.updatedAt = now
}

@Service
class QuestionGenerationProcessService(
    private val sagas: QuestionGenerationSagaPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val users: UserPort,
    private val contentLocalizations: ContentLocalizationPort,
) : GetQuestionGenerationProcessUseCase {
    @Transactional(readOnly = true)
    override suspend fun get(principal: Principal, correlationId: String): QuestionGenerationProcessResponse {
        val saga = sagas.findByCorrelationId(correlationId)
            ?.takeIf { it.userId == principal.userId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Question process not found.")
        val language = QuestionLanguage.normalize(users.findById(principal.userId)?.appLanguage)
        val question = saga.questionId
            ?.let { questions.findByIdAndUserIdAndDeletedAtIsNull(it, principal.userId) }
            ?.let { entity ->
                entity.applyReadyQuestionLocalization(
                    contentLocalizations.record(entity.id, language),
                    language,
                )
                    .toStudyRecord(questionStats.findById(entity.id))
                    .toProjection()
                    .toRecordResponse()
            }
        val terminal = saga.status == QuestionGenerationStatus.COMPLETED ||
            saga.status == QuestionGenerationStatus.FAILED
        return QuestionGenerationProcessResponse(
            correlationId = saga.correlationId,
            status = saga.status,
            currentStep = saga.currentStep,
            terminal = terminal,
            pollAfterMs = if (terminal) null else 250,
            questionId = saga.questionId?.toString(),
            question = question,
            failedStep = saga.failedStep,
            error = saga.errorCode?.let {
                QuestionGenerationErrorResponse(
                    code = it,
                    message = saga.errorMessage ?: "질문을 생성하지 못했습니다.",
                    retryable = it == "QUESTION_GENERATION_FAILED" || it == "QUESTION_TRANSLATION_FAILED",
                )
            },
            updatedAt = saga.updatedAt,
            completedAt = saga.completedAt,
        )
    }
}
