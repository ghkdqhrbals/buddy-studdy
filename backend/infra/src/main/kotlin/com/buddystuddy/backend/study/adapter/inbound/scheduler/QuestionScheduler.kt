package com.buddystuddy.backend.study.adapter.inbound.scheduler

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class QuestionScheduler(
    private val properties: BuddyStuddyProperties,
    private val studies: StudyPort,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val cipher: KeyCipher,
    private val openAI: OpenAIPort,
    private val pushOutbox: QuestionPushOutboxPort,
    private val backoffPolicy: ScheduleBackoffPolicy = ScheduleBackoffPolicy(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val creator = ScheduledQuestionCreator(
        properties = properties,
        users = users,
        questions = questions,
        questionStats = questionStats,
        cipher = cipher,
        openAI = openAI,
        pushOutbox = pushOutbox,
        backoffPolicy = backoffPolicy,
        log = log,
    )

    @Scheduled(fixedDelayString = "\${buddystuddy.scheduler.poll-ms:30000}")
    @Transactional
    fun runScheduled() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val usersById = mutableMapOf<Long, UserEntity?>()
        val recentQuestionsByUserId = mutableMapOf<Long, List<String>>()
        val dueStudies = studies.findDue(now, PageRequest.of(0, 50))
        val pendingCounts = pendingCounts(dueStudies)
        dueStudies.forEach { study ->
            creator.createIfReady(
                study = study,
                now = now,
                pending = pendingCounts[study.id] ?: 0L,
                usersById = usersById,
                recentQuestionsByUserId = recentQuestionsByUserId,
            )
        }
    }

    private fun pendingCounts(dueStudies: List<StudyEntity>): Map<Long, Long> {
        if (dueStudies.isEmpty()) return emptyMap()
        return questions.countPendingByStudyIds(dueStudies.map { it.id })
    }
}

class ScheduledQuestionCreator(
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val cipher: KeyCipher,
    private val openAI: OpenAIPort,
    private val pushOutbox: QuestionPushOutboxPort,
    private val backoffPolicy: ScheduleBackoffPolicy,
    private val log: org.slf4j.Logger,
) {
    fun createIfReady(
        study: StudyEntity,
        now: Instant,
        pending: Long,
        usersById: MutableMap<Long, UserEntity?>,
        recentQuestionsByUserId: MutableMap<Long, List<String>>,
    ) {
        try {
            val userId = study.userId
            val user = usersById.getOrPut(userId) { users.findById(userId).orElse(null) }
            val appLanguage = user?.appLanguage ?: "ko"
            if (pending >= properties.scheduler.maxPendingPerStudy) {
                study.applyRetry("Pending question limit reached ($pending).", backoffPolicy.pendingLimitNextDueAt(now), now)
                log.info("scheduled_question_skipped_pending deviceId={} userId={} studyId={} topic={} pending={}", study.deviceId, userId, study.id, study.topic, pending)
                return
            }
            val apiKey = cipher.decrypt(user?.openaiApiKeyCipher) ?: properties.openai.apiKey
            if (apiKey.isBlank()) {
                study.applyRetry("No OpenAI API key configured for study.", backoffPolicy.missingApiKeyNextDueAt(now), now)
                log.info("scheduled_question_skipped_missing_api_key deviceId={} userId={} studyId={} topic={}", study.deviceId, userId, study.id, study.topic)
                return
            }
            val recent = recentQuestionsByUserId.getOrPut(userId) {
                questions.findVisibleByUser(userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
            }
            val generated = openAI.generateQuestion(apiKey, study.openaiModel, study.topic, study.difficultyLevel, appLanguage, study.customPrompt, recent)
            val saved = questions.save(study.toScheduledQuestion(generated.question, generated.hint, now))
            questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
            pushOutbox.enqueue(study.toPushRequest(saved.id, generated.question, generated.hint, appLanguage, now), now)
            study.applySuccess(now)
            log.info("scheduled_question_created deviceId={} userId={} studyId={} topic={} questionId={} pushOutbox=true", study.deviceId, userId, study.id, study.topic, saved.id)
        } catch (error: Exception) {
            study.applyRetry(error.message ?: error.javaClass.simpleName, backoffPolicy.failureNextDueAt(now), now)
            log.warn("scheduled_question_failed deviceId={} userId={} studyId={} topic={} error={}", study.deviceId, study.userId, study.id, study.topic, error.message)
        }
    }

    private fun StudyEntity.toScheduledQuestion(question: String, hint: String?, now: Instant): QuestionEntity =
        QuestionEntity(
            deviceId = deviceId,
            userId = userId,
            studyId = id,
            question = question,
            hint = hint,
            topic = topic,
            difficultyLevel = difficultyLevel,
            scheduledFor = nextDueAt ?: now,
            sentAt = now,
            status = "ungraded",
            source = "scheduled",
            publicQuestion = true,
            createdAt = now,
            updatedAt = now,
        )

    private fun StudyEntity.toPushRequest(
        recordId: Long,
        question: String,
        hint: String?,
        appLanguage: String,
        now: Instant,
    ): QuestionPushRequest =
        QuestionPushRequest(
            recordId = recordId,
            studyId = id,
            createdAt = now,
            deviceId = deviceId,
            userId = userId,
            question = question,
            expectedAnswerHint = hint,
            topic = topic,
            difficultyLevel = difficultyLevel,
            language = appLanguage,
            sound = notificationSound,
            intervalMinutes = intervalMinutes,
        )

    private fun StudyEntity.applySuccess(now: Instant) {
        lastSentAt = now
        nextDueAt = now.plusSeconds(intervalMinutes.toLong() * 60)
        lastError = null
        updatedAt = now
    }

    private fun StudyEntity.applyRetry(error: String, nextDueAt: Instant, now: Instant) {
        lastError = error
        this.nextDueAt = nextDueAt
        updatedAt = now
    }
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
