package com.buddystuddy.backend.study.application.service

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystuddy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionCreatedPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.backend.study.application.openai.OpenAIQuestionKey
import com.buddystuddy.backend.study.application.openai.OpenAIQuestionKeyProvider
import com.buddystuddy.backend.study.application.prompt.QuestionPromptProvider
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Service
class ScheduledQuestionService(
    private val properties: BuddyStuddyProperties,
    private val studies: StudyPort,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
    private val openAI: OpenAIPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val backoffPolicy: ScheduleBackoffPolicy = ScheduleBackoffPolicy(),
) : RunQuestionScheduleUseCase {
    private val log = LoggerFactory.getLogger(javaClass)
    private val creator = ScheduledQuestionCreator(
        properties = properties,
        users = users,
        questions = questions,
        questionStats = questionStats,
        questionCreatedPublisher = questionCreatedPublisher,
        notifications = notifications,
        openAI = openAI,
        questionKeys = questionKeys,
        questionPrompts = questionPrompts,
        backoffPolicy = backoffPolicy,
        log = log,
    )

    @Transactional
    override fun runDueQuestions() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val usersById = mutableMapOf<Long, UserEntity?>()
        val recentQuestionsByUserId = mutableMapOf<Long, List<String>>()
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
                    recentQuestionsByUserId = recentQuestionsByUserId,
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
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val questionCreatedPublisher: QuestionCreatedPublishPort,
    private val notifications: PublishNotificationUseCase,
    private val openAI: OpenAIPort,
    private val questionKeys: OpenAIQuestionKeyProvider,
    private val questionPrompts: QuestionPromptProvider,
    private val backoffPolicy: ScheduleBackoffPolicy,
    private val log: Logger,
) {
    fun createIfReady(
        study: StudyEntity,
        now: Instant,
        pending: Long,
        usersById: MutableMap<Long, UserEntity?>,
        recentQuestionsByUserId: MutableMap<Long, List<String>>,
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
            val recent = recentQuestionsByUserId.getOrPut(userId) {
                questions.findVisibleByUser(userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
            }
            val prompt = questionPrompts.buildQuestionGenerationPrompt(
                topic = study.topic,
                level = study.difficultyLevel,
                language = appLanguage,
                customPrompt = study.customPrompt,
                recentQuestions = recent,
            )
            val generated = openAI.generateQuestion(questionKey.apiKey, study.openaiModel, prompt)
            val saved = questions.save(study.toScheduledQuestion(generated.question, generated.hint, appLanguage, now))
            questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
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
