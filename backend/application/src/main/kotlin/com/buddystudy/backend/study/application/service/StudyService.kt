package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.content.MarkdownContentPolicy
import com.buddystudy.backend.study.application.content.QuestionNotificationContentPolicy
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.localizedFor
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyRecordWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.reflect.jvm.internal.KotlinReflectionInternalError

@Service
class StudyService(
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val recordWriter: StudyRecordWriteUseCase,
    private val gradingWriter: AnswerGradingWriteUseCase,
    private val outboxPublisher: PublishOutboxUseCase,
) : StudyUseCase, BrowseRecordsUseCase {
    override suspend fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val saved = if (grade) {
            val queued = gradingWriter.queue(
                userId = principal.userId,
                recordId = recordId,
                answer = answer,
                now = Instant.now(),
            )
            outboxPublisher.publishNow(queued.outboxes)
            queued.question
        } else {
            recordWriter.answer(
                userId = principal.userId,
                recordId = recordId,
                answer = answer,
                grade = null,
                now = Instant.now(),
            )
        }
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection().toRecordResponse()
    }

    @Transactional(readOnly = true)
    override suspend fun records(principal: Principal, limit: Int, offset: Int, query: String?, language: String): RecordsPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedLanguage = QuestionLanguage.normalize(language)
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (search == null) {
            questions.findVisibleByUserAndLanguage(
                principal.userId,
                includePending = false,
                normalizedLanguage,
                pageable,
            )
        } else {
            questions.findVisibleByUserAndLanguageAndQuery(
                principal.userId,
                includePending = false,
                normalizedLanguage,
                search,
                pageable,
            )
        }
        return RecordsPageResponse(
            page.content.map { it.localizedFor(normalizedLanguage) }.toRecordResponses(),
            page.totalElements,
            limit,
            offset,
        )
    }

    @Transactional(readOnly = true)
    override suspend fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.toRecordResponses(), page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override suspend fun record(principal: Principal, id: Long, language: String): StudyRecordResponse {
        val normalizedLanguage = QuestionLanguage.normalize(language)
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?.takeIf {
                if (normalizedLanguage == QuestionLanguage.ENGLISH) {
                    it.translationStatus == "READY" && !it.questionEn.isNullOrBlank()
                } else {
                    QuestionLanguage.normalize(it.language) == QuestionLanguage.KOREAN
                }
            }
            ?.localizedFor(normalizedLanguage)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        if (question.skippedAt != null) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        }
        return question.toStudyRecord(questionStats.findById(id))
            .toProjection()
            .toRecordResponse()
    }

    override suspend fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val saved = recordWriter.skip(principal.userId, id)
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection().toRecordResponse()
    }

    override suspend fun delete(principal: Principal, id: Long) {
        recordWriter.delete(principal.userId, id, Instant.now())
    }

    override suspend fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val saved = recordWriter.updatePublicity(principal.userId, id, isPublic)
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection().toRecordResponse()
    }

    private suspend fun List<QuestionEntity>.toRecordResponses(): List<StudyRecordResponse> {
        if (isEmpty()) return emptyList()
        val statsByQuestionId = questionStats.findAllByIds(map { it.id }).associateBy { it.questionId }
        return map { question ->
            question.toStudyRecord(statsByQuestionId[question.id])
                .toProjection()
                .toRecordResponse()
        }
    }
}

internal fun QuestionEntity.applyCoverage(selection: QuestionCoverageSelection?): QuestionEntity {
    if (selection == null) return this
    conceptId = selection.conceptId
    conceptKey = selection.conceptKey
    angleKey = selection.angleKey
    return this
}

internal fun defaultCoverageBlueprint(topic: String): List<QuestionCoveragePort.CoverageConceptBlueprint> =
    listOf(
        QuestionCoveragePort.CoverageConceptBlueprint(
            key = topic.ifBlank { "general" },
            name = topic.ifBlank { "General" },
            angles = listOf(
                QuestionCoveragePort.CoverageAngleBlueprint("definition", "Definition"),
                QuestionCoveragePort.CoverageAngleBlueprint("trade_off", "Trade-off"),
                QuestionCoveragePort.CoverageAngleBlueprint("failure_mode", "Failure Mode"),
                QuestionCoveragePort.CoverageAngleBlueprint("debugging", "Debugging"),
            ),
        )
    )

internal fun List<OpenAIPort.QuestionCoverageConcept>.toCoverageBlueprints(): List<QuestionCoveragePort.CoverageConceptBlueprint> =
    map { concept ->
        QuestionCoveragePort.CoverageConceptBlueprint(
            key = concept.key,
            name = concept.name,
            angles = concept.angles.map { QuestionCoveragePort.CoverageAngleBlueprint(it.key, it.name) },
            children = concept.children.toCoverageBlueprints(),
        )
    }

internal fun QuestionEntity.toQuestionNotification(study: StudyEntity, appLanguage: String): NotificationRequestCommand =
    NotificationRequestCommand(
        eventId = "question-created-$id",
        userId = study.userId,
        type = "STUDY_QUESTION",
        title = QuestionNotificationContentPolicy.title(appLanguage),
        body = MarkdownContentPolicy.plainText(question),
        threadType = "study_question",
        threadId = id.toString(),
        deepLink = "buddystudy://records/$id",
        metadataJson = QuestionNotificationMetadata(
            recordId = id,
            studyId = checkNotNull(studyId) { "Question notification requires a study id." },
            topic = topic,
            difficultyLevel = difficultyLevel,
            language = appLanguage,
            sound = study.notificationSound,
            intervalMinutes = study.intervalMinutes,
        ).toJson(),
        shouldPush = true,
    )

internal fun QuestionEntity.toQuestionPushRequest(study: StudyEntity, appLanguage: String): QuestionPushRequest =
    QuestionPushRequest(
        recordId = id,
        studyId = studyId,
        deviceId = deviceId,
        userId = userId,
        question = question,
        expectedAnswerHint = hint,
        topic = topic,
        difficultyLevel = difficultyLevel,
        language = appLanguage,
        sound = study.notificationSound,
        intervalMinutes = study.intervalMinutes,
        title = QuestionNotificationContentPolicy.title(appLanguage),
        body = MarkdownContentPolicy.plainText(question),
        deepLink = "buddystudy://records/$id",
        createdAt = createdAt,
    )

internal fun QuestionNotificationMetadata.toJson(): String =
    translateNotificationMetadataSerializationError {
        JsonMapperProvider.mapper.writeValueAsString(this)
    }

internal fun <T> translateNotificationMetadataSerializationError(block: () -> T): T =
    try {
        block()
    } catch (error: KotlinReflectionInternalError) {
        throw QuestionNotificationSerializationException(error)
    } catch (error: LinkageError) {
        throw QuestionNotificationSerializationException(error)
    }

internal class QuestionNotificationSerializationException(
    cause: Error,
) : IllegalStateException(
    "Question notification metadata serialization failed (${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}).",
    cause,
)

internal data class QuestionNotificationMetadata(
    val recordId: Long,
    val studyId: Long,
    val topic: String,
    val difficultyLevel: Int,
    val language: String,
    val sound: String?,
    val intervalMinutes: Int,
)
