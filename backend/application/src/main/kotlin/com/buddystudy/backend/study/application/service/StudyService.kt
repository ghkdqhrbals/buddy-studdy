package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.study.application.model.RecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.study.domain.StudyRoom
import com.buddystudy.study.domain.StudyRoomPendingLimitExceeded
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.StudyUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionCoverageGuide
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.reflect.jvm.internal.KotlinReflectionInternalError
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudyService(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    @param:Qualifier("openAIClient")
    private val openAI: OpenAIPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val users: UserPort,
    private val cipher: KeyCipher,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val questionDiversity: QuestionDiversityPolicy,
    private val questionWriter: QuestionCreationWriteManager,
    private val recordWriter: StudyRecordWriteManager,
    private val questionSimilarity: QuestionSimilarityPolicy = QuestionSimilarityPolicy(),
) : StudyUseCase, BrowseRecordsUseCase {
    @RequirePermission(Permissions.STUDY_CREATE)
    override suspend fun createQuestion(principal: Principal, studyId: Long): StudyRecordResponse =
        createQuestionAsync(principal, studyId)

    private suspend fun createQuestionAsync(principal: Principal, studyId: Long): StudyRecordResponse = coroutineScope {
        val studyDeferred = async { studies.findByIdAndUserId(studyId, principal.userId) }
        val userDeferred = async { users.findById(principal.userId) }

        val requestedStudy = studyDeferred.await()
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study not found.")
        val userStudies = studies.findAllByUserId(principal.userId)
        val rootStudy = StudyTreeSelector.rootFor(requestedStudy, userStudies)
        val user = userDeferred.await()
        val appLanguage = user?.appLanguage ?: "ko"

        val pendingCountDeferred = async { questions.countPendingForStudy(rootStudy.id) }
        val recentQuestionsDeferred = async { recentQuestions(principal, rootStudy.id, requestedStudy.topic) }
        val room = StudyRoom.of(
            requestedStudy.toStudyRoomSchedule(
                appLanguage = appLanguage,
                questionStudyId = rootStudy.id,
                questionSettings = rootStudy,
            ),
            pendingCountDeferred.await(),
        )
        try {
            room.canCreateQuestion(properties.scheduler.maxPendingPerStudy)
        } catch (error: StudyRoomPendingLimitExceeded) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }
        val questionKey = questionKeys.resolveForQuestionGeneration(user)
        try {
            val recentEmbeddingsDeferred = async {
                questionEmbeddings.findRecentByStudyIdAndTopic(rootStudy.id, requestedStudy.topic, RECENT_EMBEDDING_LIMIT)
            }
            val coverageSelectionDeferred = async {
                selectCoverage(
                    apiKey = questionKey.apiKey,
                    topicStudy = requestedStudy,
                    questionSettings = rootStudy,
                )
            }
            val generatedQuestionDeferred = async {
                val coverageSelection = coverageSelectionDeferred.await()
                generateDistinctQuestion(
                    apiKey = questionKey.apiKey,
                    model = room.openaiModel.ifBlank { properties.openai.model },
                    topic = room.topic,
                    level = room.difficultyLevel,
                    language = room.appLanguage,
                    customPrompt = room.customPrompt,
                    studyId = requestedStudy.id,
                    userId = principal.userId,
                    recentQuestions = recentQuestionsDeferred.await(),
                    recentEmbeddings = recentEmbeddingsDeferred.await(),
                    coverageSelection = coverageSelection,
                )
            }

            val generated = generatedQuestionDeferred.await()
            val coverageSelection = coverageSelectionDeferred.await()
            val now = Instant.now()

            val questionDeferred = async {
                val question = room.createQuestion(generated.question, generated.hint, source = "manual", now = now)
                    .toQuestionEntity()
                    .applyCoverage(coverageSelection)
                val saved = questionWriter.saveQuestionWithNotification(
                    embedding = generated.embedding,
                    coverage = coverageSelection,
                    questionKey = questionKey,
                    question = question,
                    notification = { saved -> saved.toQuestionNotification(rootStudy, appLanguage) },
                    now = now,
                )
                saved
            }

            val question = questionDeferred.await()
            question.toStudyRecord().toProjection().toRecordResponse()
        } catch (error: Exception) {
            questionKeys.releaseQuestionReservation(questionKey)
            throw error
        }
    }

    override suspend fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        val user = users.findById(principal.userId)
        val graded = if (grade && question.score == null) {
            val study = question.studyId?.let { studies.findByIdAndUserId(it, principal.userId) }
                ?: studies.findByUserIdAndTopic(principal.userId, question.topic)
                ?: studies.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
            openAI.grade(
                apiKeyFor(user),
                openAIModelFor(study),
                question.question,
                answer,
                question.topic,
                question.difficultyLevel,
                user?.appLanguage ?: "ko",
            )
        } else {
            null
        }
        val saved = recordWriter.answer(
            userId = principal.userId,
            recordId = recordId,
            answer = answer,
            grade = graded,
            now = Instant.now(),
        )
        return saved.toStudyRecord(questionStats.findById(saved.id)).toProjection().toRecordResponse()
    }

    @Transactional(readOnly = true)
    override suspend fun records(principal: Principal, limit: Int, offset: Int, query: String?, language: String): RecordsPageResponse {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(offset / limit, limit)
        val page = if (search == null) {
            questions.findVisibleByUser(principal.userId, includePending = false, pageable)
        } else {
            questions.findVisibleByUserAndQuery(principal.userId, includePending = false, search, pageable)
        }
        return RecordsPageResponse(page.content.toRecordResponses(), page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override suspend fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.toRecordResponses(), page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    override suspend fun record(principal: Principal, id: Long, language: String): StudyRecordResponse {
        val question = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
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

    private suspend fun apiKeyFor(user: com.buddystudy.account.domain.entity.UserEntity?): String {
        return cipher.decrypt(user?.openaiApiKeyCipher)
            ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")
    }

    private suspend fun openAIModelFor(study: StudyEntity?): String = study?.openaiModel?.takeIf { it.isNotBlank() } ?: properties.openai.model

    private suspend fun recentQuestions(principal: Principal, rootStudyId: Long, topic: String): List<String> {
        val sameStudy = questions.findRecentQuestionTextsByStudyIdAndTopic(rootStudyId, topic, PageRequest.of(0, 30))
        val sameTopic = questions.findRecentQuestionTextsByUserIdAndTopic(principal.userId, topic, PageRequest.of(0, 30))
        return (sameStudy + sameTopic)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedQuestionKey() }
            .take(40)
    }

    private suspend fun generateDistinctQuestion(
        apiKey: String,
        model: String,
        topic: String,
        level: Int,
        language: String,
        customPrompt: String,
        studyId: Long,
        userId: Long,
        recentQuestions: List<String>,
        recentEmbeddings: List<QuestionEmbeddingCandidate>,
        coverageSelection: QuestionCoverageSelection?,
    ): GeneratedQuestionWithEmbedding {
        val maxAttempts = properties.openai.questionSimilarityMaxAttempts.coerceAtLeast(1)
        val rejectedQuestions = mutableListOf<String>()
        repeat(maxAttempts) { attempt ->
            val history = recentQuestions + rejectedQuestions
            val prompt = questionPrompts.buildQuestionGenerationPrompt(
                topic = topic,
                level = level,
                language = language,
                customPrompt = customPrompt,
                recentQuestions = history,
                diversity = questionDiversity.choose(topic, studyId, userId, history),
                coverage = coverageSelection?.let {
                    QuestionCoverageGuide(
                        conceptName = it.conceptName,
                        angleName = it.angleName,
                        conceptPath = it.conceptPath,
                    )
                },
            )
            val generated = openAI.generateQuestion(apiKey, model, prompt)
            val embedding = openAI.embedText(apiKey, generated.question)
            val similar = questionSimilarity.findSimilar(
                embedding = embedding,
                candidates = recentEmbeddings,
                threshold = properties.openai.questionSimilarityThreshold,
            )
            if (similar == null) {
                return GeneratedQuestionWithEmbedding(generated, embedding)
            }
            rejectedQuestions += generated.question
            if (attempt == maxAttempts - 1) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    ApiErrorCode.VALIDATION_ERROR,
                    "Generated question is too similar to a previous question.",
                )
            }
        }
        error("unreachable")
    }

    private suspend fun selectCoverage(
        apiKey: String,
        topicStudy: StudyEntity,
        questionSettings: StudyEntity,
    ): QuestionCoverageSelection? {
        questionCoverage.selectNext(topicStudy.id)?.let { return it }
        val blueprint = openAI.generateQuestionCoverageBlueprint(
            apiKey = apiKey,
            model = questionSettings.openaiModel.ifBlank { properties.openai.model },
            topic = topicStudy.topic,
            level = topicStudy.difficultyLevel,
            customPrompt = questionSettings.customPrompt,
        ).map { concept ->
            QuestionCoveragePort.CoverageConceptBlueprint(
                key = concept.key,
                name = concept.name,
                angles = concept.angles.map { QuestionCoveragePort.CoverageAngleBlueprint(it.key, it.name) },
                children = concept.children.toCoverageBlueprints(),
            )
        }
        questionCoverage.ensureCoverage(
            topicStudy.id,
            topicStudy.topic,
            blueprint.ifEmpty { defaultCoverageBlueprint(topicStudy.topic) },
        )
        return questionCoverage.selectNext(topicStudy.id)
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

private const val RECENT_EMBEDDING_LIMIT = 200

private suspend fun String.normalizedQuestionKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

internal fun QuestionEntity.toQuestionNotification(study: StudyEntity, appLanguage: String): NotificationRequestCommand =
    NotificationRequestCommand(
        eventId = "question-created-$id",
        userId = study.userId,
        type = "STUDY_QUESTION",
        title = "BuddyStudy",
        body = question,
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
