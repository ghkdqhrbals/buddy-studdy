package com.buddystuddy.backend.study.application.service

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.study.application.port.inbound.RunQuestionScheduleUseCase
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyQuestionJobPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.study.domain.entity.StudyEntity
import com.buddystuddy.study.domain.entity.StudyQuestionJobEntity
import com.buddystuddy.study.domain.entity.StudyQuestionJobStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ScheduledQuestionService(
    private val properties: BuddyStuddyProperties,
    private val studies: StudyPort,
    private val jobs: StudyQuestionJobPort,
    private val users: UserPort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
    private val cipher: KeyCipher,
    private val openAI: OpenAIPort,
    private val pushOutbox: QuestionPushOutboxPort,
    private val backoffPolicy: ScheduleBackoffPolicy = ScheduleBackoffPolicy(),
) : RunQuestionScheduleUseCase {
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

    @Transactional
    override fun runDueQuestions() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val recovered = jobs.recoverStaleProcessing(now.minusSeconds(properties.scheduler.processingTimeoutSeconds), now)
        if (recovered > 0) {
            log.warn("scheduled_question_jobs_recovered count={}", recovered)
        }
        val usersById = mutableMapOf<Long, UserEntity?>()
        val recentQuestionsByUserId = mutableMapOf<Long, List<String>>()
        val batchSize = properties.scheduler.batchSize.coerceAtLeast(1)
        val workerId = properties.scheduler.workerId.ifBlank { "backend-${ProcessHandle.current().pid()}" }
        var processed = 0
        while (true) {
            val dueJobs = jobs.claimDue(now, batchSize)
            if (dueJobs.isEmpty()) break
            dueJobs.forEach { it.markProcessing(workerId, now) }
            jobs.saveBatch(dueJobs)

            val dueStudies = dueJobs.mapNotNull { job ->
                studies.findByIdAndUserId(job.studyId, job.userId)
                    ?.takeIf { it.enabled }
                    ?: run {
                        job.markCanceled("Study is missing or disabled.", now)
                        null
                    }
            }
            val pendingCounts = pendingCounts(dueJobs.map { it.studyId })
            val studiesById = dueStudies.associateBy { it.id }
            dueJobs.forEach { job ->
                val study = studiesById[job.studyId] ?: return@forEach
                creator.createIfReady(
                    job = job,
                    study = study,
                    now = now,
                    pending = pendingCounts[job.studyId] ?: 0L,
                    usersById = usersById,
                    recentQuestionsByUserId = recentQuestionsByUserId,
                )
                if (job.status == StudyQuestionJobStatus.COMPLETED && study.enabled) {
                    jobs.save(study.toNextJob(now.plusSeconds(study.intervalMinutes.toLong() * 60), now))
                }
            }
            jobs.saveBatch(dueJobs)
            processed += dueJobs.size
        }
        if (processed > 0) {
            log.info("scheduled_question_drain_completed processed={} batchSize={}", processed, batchSize)
        }
    }

    private fun pendingCounts(studyIds: List<Long>): Map<Long, Long> {
        if (studyIds.isEmpty()) return emptyMap()
        return questions.countPendingByStudyIds(studyIds)
    }

    private fun StudyEntity.toNextJob(scheduledAt: Instant, now: Instant): StudyQuestionJobEntity =
        StudyQuestionJobEntity(
            studyId = id,
            deviceId = deviceId,
            userId = userId,
            scheduledAt = scheduledAt,
            status = StudyQuestionJobStatus.SCHEDULED,
            createdAt = now,
            updatedAt = now,
        )
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
    private val log: Logger,
) {
    fun createIfReady(
        job: StudyQuestionJobEntity,
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
                job.markScheduled("Pending question limit reached ($pending).", backoffPolicy.pendingLimitNextDueAt(now), now)
                log.info("scheduled_question_skipped_pending deviceId={} userId={} studyId={} jobId={} topic={} pending={}", study.deviceId, userId, study.id, job.id, study.topic, pending)
                return
            }
            val apiKey = cipher.decrypt(user?.openaiApiKeyCipher) ?: properties.openai.apiKey
            if (apiKey.isBlank()) {
                job.markScheduled("No OpenAI API key configured for study.", backoffPolicy.missingApiKeyNextDueAt(now), now)
                log.info("scheduled_question_skipped_missing_api_key deviceId={} userId={} studyId={} jobId={} topic={}", study.deviceId, userId, study.id, job.id, study.topic)
                return
            }
            val recent = recentQuestionsByUserId.getOrPut(userId) {
                questions.findVisibleByUser(userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
            }
            val generated = openAI.generateQuestion(apiKey, study.openaiModel, study.topic, study.difficultyLevel, appLanguage, study.customPrompt, recent)
            val saved = questions.save(study.toScheduledQuestion(job, generated.question, generated.hint, now))
            questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
            pushOutbox.enqueue(study.toPushRequest(saved.id, generated.question, generated.hint, appLanguage, now), now)
            job.markCompleted(saved.id, now)
            log.info("scheduled_question_created deviceId={} userId={} studyId={} jobId={} topic={} questionId={} pushOutbox=true", study.deviceId, userId, study.id, job.id, study.topic, saved.id)
        } catch (error: Exception) {
            job.markScheduled(error.message ?: error.javaClass.simpleName, backoffPolicy.failureNextDueAt(now), now)
            log.warn("scheduled_question_failed deviceId={} userId={} studyId={} jobId={} topic={} error={}", study.deviceId, study.userId, study.id, job.id, study.topic, error.message)
        }
    }

    private fun StudyEntity.toScheduledQuestion(job: StudyQuestionJobEntity, question: String, hint: String?, now: Instant): QuestionEntity =
        QuestionEntity(
            deviceId = deviceId,
            userId = userId,
            studyId = id,
            question = question,
            hint = hint,
            topic = topic,
            difficultyLevel = difficultyLevel,
            scheduledFor = job.scheduledAt,
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

}

private fun StudyQuestionJobEntity.markProcessing(workerId: String, now: Instant) {
    status = StudyQuestionJobStatus.PROCESSING
    lockedAt = now
    lockedBy = workerId
    attemptCount += 1
    updatedAt = now
}

private fun StudyQuestionJobEntity.markScheduled(error: String, scheduledAt: Instant, now: Instant) {
    status = StudyQuestionJobStatus.SCHEDULED
    this.scheduledAt = scheduledAt
    lockedAt = null
    lockedBy = null
    lastError = error
    updatedAt = now
}

private fun StudyQuestionJobEntity.markCompleted(questionId: Long, now: Instant) {
    status = StudyQuestionJobStatus.COMPLETED
    createdQuestionId = questionId
    completedAt = now
    lockedAt = null
    lockedBy = null
    lastError = null
    updatedAt = now
}

private fun StudyQuestionJobEntity.markCanceled(reason: String, now: Instant) {
    status = StudyQuestionJobStatus.CANCELED
    canceledAt = now
    lockedAt = null
    lockedBy = null
    lastError = reason
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
