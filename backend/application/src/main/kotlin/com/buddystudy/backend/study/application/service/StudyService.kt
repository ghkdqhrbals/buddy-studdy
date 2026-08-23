package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
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
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.port.ContentLanguageDetectionPort
import com.buddystudy.backend.localization.application.port.ContentLocalizationPort
import com.buddystudy.backend.localization.application.port.RequestContentLocalizationUseCase
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyRecordWriteUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.study.application.port.inbound.QuestionWriteResult
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
    private val users: UserPort,
    private val languageDetector: ContentLanguageDetectionPort,
    private val contentLocalizations: ContentLocalizationPort,
    private val localizationRequests: RequestContentLocalizationUseCase,
) : StudyUseCase, BrowseRecordsUseCase {
    override suspend fun answer(
        principal: Principal,
        recordId: Long,
        answer: String,
        sourceLanguage: String?,
        grade: Boolean,
    ): StudyRecordResponse {
        val appLanguage = QuestionLanguage.normalize(
            users.findById(principal.userId)?.appLanguage?.databaseValue,
        )
        val declaredSourceLanguage = sourceLanguage
            ?.takeIf { QuestionLanguage.normalize(it) in QuestionLanguage.supported }
            ?.let(QuestionLanguage::normalize)
            ?: appLanguage
        val normalizedSourceLanguage = languageDetector.detect(answer, declaredSourceLanguage)
        val written = if (grade) {
            val queued = gradingWriter.queue(
                userId = principal.userId,
                recordId = recordId,
                answer = answer,
                sourceLanguage = normalizedSourceLanguage,
                aiResponseLanguage = appLanguage,
                now = Instant.now(),
            )
            QuestionWriteResult(
                queued.question,
                queued.outboxes,
            )
        } else {
            recordWriter.answer(
                userId = principal.userId,
                recordId = recordId,
                answer = answer,
                sourceLanguage = normalizedSourceLanguage,
                grade = null,
                now = Instant.now(),
            )
        }
        outboxPublisher.publishNow(written.outboxes)
        val saved = written.question
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection()
            .toRecordResponse()
    }

    @Transactional(readOnly = true)
    override suspend fun records(
        principal: Principal,
        limit: Int,
        offset: Int,
        query: String?,
        studyId: Long?,
        language: String,
        view: String,
    ): RecordsPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedLanguage = QuestionLanguage.normalize(language)
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (studyId != null) {
            questions.findVisibleByUserAndStudyId(
                principal.userId,
                includePending = false,
                studyId,
                search,
                pageable,
            )
        } else if (search == null) {
            questions.findVisibleByUser(
                principal.userId,
                includePending = false,
                pageable,
            )
        } else {
            questions.findVisibleByUserAndQuery(
                principal.userId,
                includePending = false,
                search,
                pageable,
            )
        }
        val viewMode = translationViewMode(view)
        return RecordsPageResponse(
            page.content.toRecordResponses(normalizedLanguage, viewMode),
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
    override suspend fun record(principal: Principal, id: Long, language: String, view: String): StudyRecordResponse {
        val normalizedLanguage = QuestionLanguage.normalize(language)
        val viewMode = translationViewMode(view)
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        if (question.skippedAt != null) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        }
        val projected = project(question, normalizedLanguage, viewMode)
        return projected.question.toStudyRecord(questionStats.findById(id))
            .toProjection()
            .toRecordResponse(
                requestedLanguage = normalizedLanguage,
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

    override suspend fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val saved = recordWriter.skip(principal.userId, id)
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection()
            .toRecordResponse()
    }

    override suspend fun delete(principal: Principal, id: Long) {
        recordWriter.delete(principal.userId, id, Instant.now())
    }

    override suspend fun clear(principal: Principal) {
        recordWriter.clear(principal.userId, Instant.now())
    }

    override suspend fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val saved = recordWriter.updatePublicity(principal.userId, id, isPublic)
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection()
            .toRecordResponse()
    }

    private suspend fun List<QuestionEntity>.toRecordResponses(
        requestedLanguage: String? = null,
        viewMode: TranslationViewMode = TranslationViewMode.LOCALIZED,
    ): List<StudyRecordResponse> {
        if (isEmpty()) return emptyList()
        val statsByQuestionId = questionStats.findAllByIds(map { it.id }).associateBy { it.questionId }
        return map { question ->
            val projected = project(
                question,
                requestedLanguage ?: question.sourceLanguage.databaseValue,
                viewMode,
            )
            projected.question.toStudyRecord(statsByQuestionId[question.id])
                .toProjection()
                .toRecordResponse(
                    requestedLanguage = requestedLanguage ?: question.sourceLanguage.databaseValue,
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
    }

    private suspend fun project(
        question: QuestionEntity,
        requestedLanguage: String,
        viewMode: TranslationViewMode,
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
                !question.answer.isNullOrBlank(),
            )
        }
        val hashes = ContentSourceHashPolicy.recordHashes(question)
        val snapshot = contentLocalizations.record(question.id, target)
        val questionReady = snapshot.question.readyFor(hashes.question)
        val answerReady = snapshot.answer.readyFor(hashes.answer)
        val aiReady = snapshot.aiResponse.readyFor(hashes.aiResponse)
        val needsTranslation =
            (questionSource != target && questionReady == null) ||
                (!question.answer.isNullOrBlank() && answerSource != target && answerReady == null) ||
                ((!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) &&
                    aiSource != target && aiReady == null)
        if (needsTranslation) {
            localizationRequests.requestRecord(question, target)
        }
        var questionDisplay = questionSource
        var answerDisplay = answerSource
        var aiDisplay = aiSource
        if (questionSource != target) {
            val localized = questionReady
            if (localized != null) {
                question.topic = localized.fields["topic"] ?: question.topic
                question.question = localized.fields["question"] ?: question.question
                question.hint = localized.fields["hint"] ?: question.hint
                questionDisplay = target
            }
        }
        if (!question.answer.isNullOrBlank() && answerSource != target) {
            answerReady?.let {
                question.answer = it.fields["answer"] ?: question.answer
                answerDisplay = target
            }
        }
        if ((!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) && aiSource != target) {
            aiReady?.let {
                question.feedback = it.fields["feedback"] ?: question.feedback
                question.explanation = it.fields["explanation"] ?: question.explanation
                question.gradingAssessmentJson = it.fields["assessmentJson"] ?: question.gradingAssessmentJson
                aiDisplay = target
            }
        }
        return ProjectedRecord(
            question,
            questionDisplay,
            answerDisplay,
            aiDisplay,
            questionSource != target && questionDisplay != target && snapshot.question?.status != "FAILED",
            !question.answer.isNullOrBlank() && answerSource != target &&
                answerDisplay != target && snapshot.answer?.status != "FAILED",
            (!question.feedback.isNullOrBlank() || !question.explanation.isNullOrBlank()) &&
                aiSource != target && aiDisplay != target && snapshot.aiResponse?.status != "FAILED",
            false,
        )
    }

    private fun com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot?.readyFor(
        sourceHash: String?,
    ) = sourceHash?.let { hash -> this?.takeIf { it.status == "READY" && it.sourceHash == hash } }

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
