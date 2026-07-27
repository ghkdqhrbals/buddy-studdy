package com.buddystudy.backend.study.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.GeneratedQuestionWithEmbedding
import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystudy.backend.study.application.port.inbound.ScheduledQuestionWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.prompt.QuestionCoverageGuide
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ScheduledQuestionService(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    @param:Qualifier("openAIClient")
    private val openAI: OpenAIPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val questionDiversity: QuestionDiversityPolicy,
    private val writer: ScheduledQuestionWriteUseCase,
    private val outboxPublisher: PublishOutboxUseCase,
    private val backoffPolicy: ScheduleBackoffPolicy = ScheduleBackoffPolicy(),
) : RunQuestionScheduleUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    private val creator = ScheduledQuestionCreator(
        properties = properties,
        users = users,
        questions = questions,
        questionEmbeddings = questionEmbeddings,
        questionCoverage = questionCoverage,
        openAI = openAI,
        questionKeys = questionKeys,
        questionPrompts = questionPrompts,
        questionDiversity = questionDiversity,
        backoffPolicy = backoffPolicy,
        writer = writer,
        outboxPublisher = outboxPublisher,
        log = log,
    )

    override suspend fun runDueQuestions() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val usersById = mutableMapOf<Long, UserEntity?>()
        val studiesByUserId = mutableMapOf<Long, List<StudyEntity>>()
        val recentQuestionsByStudyTopic = mutableMapOf<StudyTopicKey, List<String>>()
        val recentEmbeddingsByStudyTopic = mutableMapOf<StudyTopicKey, List<QuestionEmbeddingCandidate>>()
        val batchSize = properties.scheduler.batchSize.coerceAtLeast(1)
        var processed = 0
        while (true) {
            val dueStudies = studies.claimDue(now, batchSize)
            if (dueStudies.isEmpty()) break
            val dueContexts = dueStudies.map { scheduleStudy ->
                val userStudies = studiesByUserId[scheduleStudy.userId]
                    ?: studies.findAllByUserId(scheduleStudy.userId).also {
                        studiesByUserId[scheduleStudy.userId] = it
                    }
                ScheduledStudyContext(
                    scheduleStudy = scheduleStudy,
                    userStudies = userStudies,
                    activeTopics = StudyTreeSelector.activeTopics(scheduleStudy, userStudies),
                )
            }
            val languageByUserId = dueContexts
                .map { it.scheduleStudy.userId }
                .distinct()
                .associateWith { userId ->
                    val user = usersById[userId] ?: users.findById(userId).also { usersById[userId] = it }
                    QuestionLanguage.normalize(user?.appLanguage)
                }
            val pendingCounts = pendingCounts(dueContexts, languageByUserId)
            val maxPendingPerTopic = properties.scheduler.maxPendingPerStudy.coerceAtLeast(1)
            dueContexts.forEach { context ->
                val scheduleStudy = context.scheduleStudy
                val appLanguage = languageByUserId.getValue(scheduleStudy.userId)
                if (context.activeTopics.isEmpty()) {
                    writer.deferUntilNextInterval(scheduleStudy, now)
                    log.info(
                        "scheduled_question_skipped_no_active_topic deviceId={} userId={} rootStudyId={}",
                        scheduleStudy.deviceId,
                        scheduleStudy.userId,
                        scheduleStudy.id,
                    )
                    return@forEach
                }
                val blockedTopicIds = context.activeTopics
                    .filter { topic -> (pendingCounts[topic.id to appLanguage] ?: 0L) >= maxPendingPerTopic }
                    .mapTo(mutableSetOf()) { it.id }
                val topicStudy = StudyTreeSelector.nextActiveTopic(
                    root = scheduleStudy,
                    allStudies = context.userStudies,
                    excludedStudyIds = blockedTopicIds,
                )
                if (topicStudy == null) {
                    writer.fail(
                        study = scheduleStudy,
                        questionKey = null,
                        error = "Pending question limit reached for all active topics.",
                        retryAt = backoffPolicy.pendingLimitNextDueAt(now),
                        now = now,
                    )
                    log.info(
                        "scheduled_question_skipped_all_topics_pending deviceId={} userId={} rootStudyId={} activeTopics={} blockedTopics={}",
                        scheduleStudy.deviceId,
                        scheduleStudy.userId,
                        scheduleStudy.id,
                        context.activeTopics.size,
                        blockedTopicIds.size,
                    )
                    return@forEach
                }
                creator.createIfReady(
                    scheduleStudy = scheduleStudy,
                    topicStudy = topicStudy,
                    now = now,
                    pending = pendingCounts[topicStudy.id to appLanguage] ?: 0L,
                    usersById = usersById,
                    recentQuestionsByStudyTopic = recentQuestionsByStudyTopic,
                    recentEmbeddingsByStudyTopic = recentEmbeddingsByStudyTopic,
                )
            }
            processed += dueStudies.size
        }
        if (processed > 0) {
            log.info("scheduled_question_drain_completed processed={} batchSize={}", processed, batchSize)
        }
    }

    private suspend fun pendingCounts(
        contexts: List<ScheduledStudyContext>,
        languageByUserId: Map<Long, String>,
    ): Map<Pair<Long, String>, Long> {
        val studyIdsByLanguage = contexts
            .groupBy { languageByUserId.getValue(it.scheduleStudy.userId) }
            .mapValues { (_, groupedContexts) ->
                groupedContexts.flatMap { context -> context.activeTopics.map { it.id } }.distinct()
            }
        return buildMap {
            studyIdsByLanguage.forEach { (language, studyIds) ->
                studyIds.chunked(PENDING_COUNT_BATCH_SIZE).forEach { chunk ->
                    questions.countPendingByStudyIdsAndLanguage(chunk, language).forEach { (studyId, count) ->
                        put(studyId to language, count)
                    }
                }
            }
        }
    }
}

private data class ScheduledStudyContext(
    val scheduleStudy: StudyEntity,
    val userStudies: List<StudyEntity>,
    val activeTopics: List<StudyEntity>,
)

class ScheduledQuestionCreator(
    private val properties: BuddyStudyProperties,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val openAI: OpenAIPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val questionDiversity: QuestionDiversityPolicy,
    private val backoffPolicy: ScheduleBackoffPolicy,
    private val writer: ScheduledQuestionWriteUseCase,
    private val outboxPublisher: PublishOutboxUseCase,
    private val log: Logger,
) {
    suspend fun createIfReady(
        scheduleStudy: StudyEntity,
        topicStudy: StudyEntity,
        now: Instant,
        pending: Long,
        usersById: MutableMap<Long, UserEntity?>,
        recentQuestionsByStudyTopic: MutableMap<StudyTopicKey, List<String>>,
        recentEmbeddingsByStudyTopic: MutableMap<StudyTopicKey, List<QuestionEmbeddingCandidate>>,
    ) {
        var questionKey: OpenAIQuestionKey? = null
        try {
            val userId = scheduleStudy.userId
            val user = if (usersById.containsKey(userId)) {
                usersById[userId]
            } else {
                users.findById(userId).also { usersById[userId] = it }
            }
            val appLanguage = QuestionLanguage.normalize(user?.appLanguage)
            if (pending >= properties.scheduler.maxPendingPerStudy) {
                writer.fail(
                    study = scheduleStudy,
                    questionKey = null,
                    error = "Pending question limit reached ($pending).",
                    retryAt = backoffPolicy.pendingLimitNextDueAt(now),
                    now = now,
                )
                log.info(
                    "scheduled_question_skipped_pending deviceId={} userId={} rootStudyId={} topicStudyId={} topic={} pending={}",
                    scheduleStudy.deviceId,
                    userId,
                    scheduleStudy.id,
                    topicStudy.id,
                    topicStudy.topic,
                    pending,
                )
                return
            }
            val resolvedQuestionKey = questionKeys.resolveForQuestionGeneration(user)
            questionKey = resolvedQuestionKey
            val studyTopicKey = StudyTopicKey(topicStudy.id, userId, topicStudy.topic.normalizedTopicKey())
            val recent = recentQuestionsByStudyTopic[studyTopicKey]
                ?: recentQuestions(userId, topicStudy.id, topicStudy.topic, appLanguage)
                    .also { recentQuestionsByStudyTopic[studyTopicKey] = it }
            val recentEmbeddings = recentEmbeddingsByStudyTopic[studyTopicKey]
                ?: questionEmbeddings.findRecentByStudyIdAndTopic(topicStudy.id, topicStudy.topic, RECENT_EMBEDDING_LIMIT)
                    .also { recentEmbeddingsByStudyTopic[studyTopicKey] = it }
            val coverageSelection = selectCoverage(
                apiKey = resolvedQuestionKey.apiKey,
                topicStudy = topicStudy,
                questionSettings = scheduleStudy,
            )
            val generated = generateDistinctQuestion(
                apiKey = resolvedQuestionKey.apiKey,
                model = scheduleStudy.openaiModel.ifBlank { properties.openai.model },
                topic = topicStudy.topic,
                level = topicStudy.difficultyLevel,
                language = appLanguage,
                customPrompt = scheduleStudy.customPrompt,
                studyId = topicStudy.id,
                userId = userId,
                recentQuestions = recent,
                recentEmbeddings = recentEmbeddings,
                coverageSelection = coverageSelection,
            )
            val result = writer.complete(
                scheduleStudy = scheduleStudy,
                topicStudy = topicStudy,
                generated = generated,
                coverage = coverageSelection,
                questionKey = resolvedQuestionKey,
                appLanguage = appLanguage,
                now = now,
            )
            outboxPublisher.publishNow(result.outboxes)
            val saved = result.question
            log.info(
                "scheduled_question_created deviceId={} userId={} rootStudyId={} topicStudyId={} topic={} questionId={} notification=true",
                scheduleStudy.deviceId,
                userId,
                scheduleStudy.id,
                topicStudy.id,
                topicStudy.topic,
                saved.id,
            )
        } catch (error: Exception) {
            val retryAt = if (error is ApiException && error.code == ApiErrorCode.OPENAI_API_KEY_MISSING) {
                backoffPolicy.missingApiKeyNextDueAt(now)
            } else {
                backoffPolicy.failureNextDueAt(now)
            }
            writer.fail(
                study = scheduleStudy,
                questionKey = questionKey,
                error = error.message ?: error.javaClass.simpleName,
                retryAt = retryAt,
                now = now,
            )
            log.warn(
                "scheduled_question_failed deviceId={} userId={} rootStudyId={} topicStudyId={} topic={} error={}",
                scheduleStudy.deviceId,
                scheduleStudy.userId,
                scheduleStudy.id,
                topicStudy.id,
                topicStudy.topic,
                error.message,
            )
        }
    }

    private suspend fun recentQuestions(
        userId: Long,
        rootStudyId: Long,
        topic: String,
        language: String,
    ): List<String> {
        val sameStudy = questions.findRecentQuestionTextsByStudyIdAndTopicAndLanguage(
            rootStudyId,
            topic,
            language,
            PageRequest.of(0, 30),
        )
        val sameTopic = questions.findRecentQuestionTextsByUserIdAndTopicAndLanguage(
            userId,
            topic,
            language,
            PageRequest.of(0, 30),
        )
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
        val similarityPolicy = QuestionSimilarityPolicy()
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
            if (!QuestionLanguage.matches(generated.question, language)) {
                rejectedQuestions += generated.question
                if (attempt == maxAttempts - 1) {
                    throw ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        ApiErrorCode.VALIDATION_ERROR,
                        "Generated question did not match the requested language.",
                    )
                }
                return@repeat
            }
            val embedding = openAI.embedText(apiKey, generated.question)
            val similar = similarityPolicy.findSimilar(
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

}

data class StudyTopicKey(
    val studyId: Long,
    val userId: Long,
    val topicKey: String,
)

class ScheduleBackoffPolicy(
    private val pendingLimitRetrySeconds: Long = 5 * 60,
    private val missingApiKeyRetrySeconds: Long = 30 * 60,
    private val failureRetrySeconds: Long = 10 * 60,
) {
    fun pendingLimitNextDueAt(now: Instant): Instant = now.plusSeconds(pendingLimitRetrySeconds)
    fun missingApiKeyNextDueAt(now: Instant): Instant = now.plusSeconds(missingApiKeyRetrySeconds)
    fun failureNextDueAt(now: Instant): Instant = now.plusSeconds(failureRetrySeconds)
}

private suspend fun String.normalizedTopicKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private suspend fun String.normalizedQuestionKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private const val RECENT_EMBEDDING_LIMIT = 200
private const val PENDING_COUNT_BATCH_SIZE = 500
