package com.buddystudy.backend.study.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystudy.backend.study.application.port.outbound.OpenAIPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.backend.study.application.port.outbound.QuestionCoverageSelection
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingCandidate
import com.buddystudy.backend.study.application.port.outbound.QuestionEmbeddingPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystudy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystudy.backend.study.application.prompt.QuestionCoverageGuide
import com.buddystudy.backend.study.application.prompt.QuestionDiversityPolicy
import com.buddystudy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.StudyEntity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Service
class ScheduledQuestionService(
    private val properties: BuddyStudyProperties,
    private val studies: StudyPort,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
    @param:Qualifier("openAIClient")
    private val openAI: OpenAIPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val questionDiversity: QuestionDiversityPolicy,
    private val backoffPolicy: ScheduleBackoffPolicy = ScheduleBackoffPolicy(),
) : RunQuestionScheduleUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    private val creator = ScheduledQuestionCreator(
        properties = properties,
        users = users,
        questions = questions,
        questionStats = questionStats,
        questionEmbeddings = questionEmbeddings,
        questionCoverage = questionCoverage,
        questionCreatedPublisher = questionCreatedPublisher,
        notifications = notifications,
        openAI = openAI,
        questionKeys = questionKeys,
        questionPrompts = questionPrompts,
        questionDiversity = questionDiversity,
        backoffPolicy = backoffPolicy,
        log = log,
    )

    @Transactional
    override fun runDueQuestions() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val usersById = mutableMapOf<Long, UserEntity?>()
        val recentQuestionsByStudyTopic = mutableMapOf<StudyTopicKey, List<String>>()
        val recentEmbeddingsByStudyTopic = mutableMapOf<StudyTopicKey, List<QuestionEmbeddingCandidate>>()
        val batchSize = properties.scheduler.batchSize.coerceAtLeast(1)
        var processed = 0
        while (true) {
            val dueStudies = studies.claimDue(now, batchSize)
            if (dueStudies.isEmpty()) break
            val pendingCounts = pendingCounts(dueStudies.map { it.id })
            dueStudies.forEach { study ->
                creator.createIfReady(
                    study = study,
                    now = now,
                    pending = pendingCounts[study.id] ?: 0L,
                    usersById = usersById,
                    recentQuestionsByStudyTopic = recentQuestionsByStudyTopic,
                    recentEmbeddingsByStudyTopic = recentEmbeddingsByStudyTopic,
                )
                studies.save(study)
            }
            processed += dueStudies.size
        }
        if (processed > 0) {
            log.info("scheduled_question_drain_completed processed={} batchSize={}", processed, batchSize)
        }
    }

    private fun pendingCounts(studyIds: List<Long>): Map<Long, Long> {
        if (studyIds.isEmpty()) return emptyMap()
        return questions.countPendingByStudyIds(studyIds)
    }
}

class ScheduledQuestionCreator(
    private val properties: BuddyStudyProperties,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionEmbeddings: QuestionEmbeddingPort,
    private val questionCoverage: QuestionCoveragePort,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
    private val openAI: OpenAIPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val questionDiversity: QuestionDiversityPolicy,
    private val backoffPolicy: ScheduleBackoffPolicy,
    private val log: Logger,
) {
    fun createIfReady(
        study: StudyEntity,
        now: Instant,
        pending: Long,
        usersById: MutableMap<Long, UserEntity?>,
        recentQuestionsByStudyTopic: MutableMap<StudyTopicKey, List<String>>,
        recentEmbeddingsByStudyTopic: MutableMap<StudyTopicKey, List<QuestionEmbeddingCandidate>>,
    ) {
        var questionKey: OpenAIQuestionKey? = null
        var questionCreated = false
        try {
            val userId = study.userId
            val user = usersById.getOrPut(userId) { users.findById(userId).orElse(null) }
            val appLanguage = user?.appLanguage ?: "ko"
            if (pending >= properties.scheduler.maxPendingPerStudy) {
                study.markScheduled("Pending question limit reached ($pending).", backoffPolicy.pendingLimitNextDueAt(now), now)
                log.info("scheduled_question_skipped_pending deviceId={} userId={} studyId={} topic={} pending={}", study.deviceId, userId, study.id, study.topic, pending)
                return
            }
            questionKey = questionKeys.resolveForQuestionGeneration(user)
            val recent = recentQuestionsByStudyTopic.getOrPut(StudyTopicKey(study.id, userId, study.topic.normalizedTopicKey())) {
                recentQuestions(userId, study)
            }
            val recentEmbeddings = recentEmbeddingsByStudyTopic.getOrPut(StudyTopicKey(study.id, userId, study.topic.normalizedTopicKey())) {
                questionEmbeddings.findRecentByStudyIdAndTopic(study.id, study.topic, RECENT_EMBEDDING_LIMIT)
            }
            val coverageSelection = selectCoverage(questionKey.apiKey, study)
            val generated = generateDistinctQuestion(
                apiKey = questionKey.apiKey,
                model = study.openaiModel,
                topic = study.topic,
                level = study.difficultyLevel,
                language = appLanguage,
                customPrompt = study.customPrompt,
                studyId = study.id,
                userId = userId,
                recentQuestions = recent,
                recentEmbeddings = recentEmbeddings,
                coverageSelection = coverageSelection,
            )
            val saved = questions.save(
                study.toScheduledQuestion(generated.question, generated.hint, appLanguage, now)
                    .applyCoverage(coverageSelection)
            )
            questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
            coverageSelection?.let { questionCoverage.markAsked(it, now) }
            questionEmbeddings.save(
                questionId = saved.id,
                userId = userId,
                studyId = study.id,
                topic = study.topic,
                question = saved.question,
                embedding = generated.embedding,
            )
            questionKeys.markQuestionCreated(questionKey, now)
            questionCreated = true
            study.markCompleted(now)
            afterCommit {
                questionCreatedPublisher.publishQuestionCreated(saved.id, appLanguage, now)
                notifications.publish(saved.toQuestionNotification(study, appLanguage))
            }
            log.info("scheduled_question_created deviceId={} userId={} studyId={} topic={} questionId={} notification=true", study.deviceId, userId, study.id, study.topic, saved.id)
        } catch (error: Exception) {
            if (!questionCreated) {
                questionKey?.let { questionKeys.releaseQuestionReservation(it, now) }
            }
            val retryAt = if (error is ApiException && error.code == ApiErrorCode.OPENAI_API_KEY_MISSING) {
                backoffPolicy.missingApiKeyNextDueAt(now)
            } else {
                backoffPolicy.failureNextDueAt(now)
            }
            study.markScheduled(error.message ?: error.javaClass.simpleName, retryAt, now)
            log.warn("scheduled_question_failed deviceId={} userId={} studyId={} topic={} error={}", study.deviceId, study.userId, study.id, study.topic, error.message)
        }
    }

    private fun recentQuestions(userId: Long, study: StudyEntity): List<String> {
        val sameStudy = questions.findRecentQuestionTextsByStudyIdAndTopic(study.id, study.topic, PageRequest.of(0, 30))
        val sameTopic = questions.findRecentQuestionTextsByUserIdAndTopic(userId, study.topic, PageRequest.of(0, 30))
        return (sameStudy + sameTopic)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedQuestionKey() }
            .take(40)
    }

    private fun generateDistinctQuestion(
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

    private fun selectCoverage(apiKey: String, study: StudyEntity): QuestionCoverageSelection? {
        questionCoverage.selectNext(study.id)?.let { return it }
        val blueprint = openAI.generateQuestionCoverageBlueprint(
            apiKey = apiKey,
            model = study.openaiModel.ifBlank { properties.openai.model },
            topic = study.topic,
            level = study.difficultyLevel,
            customPrompt = study.customPrompt,
        ).map { concept ->
            QuestionCoveragePort.CoverageConceptBlueprint(
                key = concept.key,
                name = concept.name,
                angles = concept.angles.map { QuestionCoveragePort.CoverageAngleBlueprint(it.key, it.name) },
                children = concept.children.toCoverageBlueprints(),
            )
        }
        questionCoverage.ensureCoverage(study.id, study.topic, blueprint.ifEmpty { defaultCoverageBlueprint(study.topic) })
        return questionCoverage.selectNext(study.id)
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action()
                }
            }
        )
    }

    private fun StudyEntity.toScheduledQuestion(
        question: String,
        hint: String?,
        appLanguage: String,
        now: Instant,
    ): QuestionEntity =
        QuestionEntity(
            deviceId = deviceId,
            userId = userId,
            studyId = id,
            question = question,
            hint = hint,
            topic = topic,
            language = appLanguage,
            difficultyLevel = difficultyLevel,
            scheduledFor = nextDueAt ?: now,
            sentAt = now,
            status = "ungraded",
            source = "scheduled",
            publicQuestion = true,
            createdAt = now,
            updatedAt = now,
        )

}

data class StudyTopicKey(
    val studyId: Long,
    val userId: Long,
    val topicKey: String,
)

private fun StudyEntity.markScheduled(error: String, scheduledAt: Instant, now: Instant) {
    nextDueAt = scheduledAt
    lastError = error
    updatedAt = now
}

private fun StudyEntity.markCompleted(now: Instant) {
    lastSentAt = now
    nextDueAt = now.plusSeconds(intervalMinutes.toLong() * 60)
    lastError = null
    updatedAt = now
}

class ScheduleBackoffPolicy(
    private val pendingLimitRetrySeconds: Long = 5 * 60,
    private val missingApiKeyRetrySeconds: Long = 30 * 60,
    private val failureRetrySeconds: Long = 10 * 60,
) {
    fun pendingLimitNextDueAt(now: Instant): Instant = now.plusSeconds(pendingLimitRetrySeconds)
    fun missingApiKeyNextDueAt(now: Instant): Instant = now.plusSeconds(missingApiKeyRetrySeconds)
    fun failureNextDueAt(now: Instant): Instant = now.plusSeconds(failureRetrySeconds)
}

private fun String.normalizedTopicKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun String.normalizedQuestionKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private const val RECENT_EMBEDDING_LIMIT = 200
