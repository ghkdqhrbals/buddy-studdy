package com.buddystuddy.backend.study.adapter.inbound.scheduler

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import com.buddystuddy.backend.study.application.port.outbound.OpenAIPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushOutboxPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${buddystuddy.scheduler.poll-ms:30000}")
    @Transactional
    fun runScheduled() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        val usersById = mutableMapOf<Long, UserEntity?>()
        val recentQuestionsByUserId = mutableMapOf<Long, List<String>>()
        studies.findDue(now, PageRequest.of(0, 50)).forEach { study ->
            try {
                val userId = study.userId
                val user = usersById.getOrPut(userId) { users.findById(userId).orElse(null) }
                val appLanguage = user?.appLanguage ?: "ko"
                val pending = questions.countPendingForStudy(study.id)
                if (pending >= properties.scheduler.maxPendingPerStudy) {
                    study.lastError = "Pending question limit reached ($pending)."
                    study.nextDueAt = now.plusSeconds(5 * 60)
                    study.updatedAt = now
                    log.info("scheduled_question_skipped_pending deviceId={} userId={} studyId={} topic={} pending={}", study.deviceId, userId, study.id, study.topic, pending)
                    return@forEach
                }
                val apiKey = cipher.decrypt(user?.openaiApiKeyCipher) ?: properties.openai.apiKey
                if (apiKey.isBlank()) {
                    study.lastError = "No OpenAI API key configured for study."
                    study.nextDueAt = now.plusSeconds(5 * 60)
                    study.updatedAt = now
                    return@forEach
                }
                val recent = recentQuestionsByUserId.getOrPut(userId) {
                    questions.findVisibleByUser(userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
                }
                val generated = openAI.generateQuestion(apiKey, study.openaiModel, study.topic, study.difficultyLevel, appLanguage, study.customPrompt, recent)
                val saved = questions.save(
                    QuestionEntity(
                        deviceId = study.deviceId,
                        userId = userId,
                        studyId = study.id,
                        question = generated.question,
                        hint = generated.hint,
                        topic = study.topic,
                        difficultyLevel = study.difficultyLevel,
                        scheduledFor = study.nextDueAt ?: now,
                        sentAt = now,
                        status = "ungraded",
                        source = "scheduled",
                        publicQuestion = true,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
                pushOutbox.enqueue(
                    QuestionPushRequest(
                        recordId = saved.id,
                        createdAt = now,
                        deviceId = study.deviceId,
                        userId = userId,
                        question = generated.question,
                        expectedAnswerHint = generated.hint,
                        topic = study.topic,
                        difficultyLevel = study.difficultyLevel,
                        language = appLanguage,
                        sound = study.notificationSound,
                        intervalMinutes = study.intervalMinutes,
                    ),
                    now,
                )
                study.lastSentAt = now
                study.nextDueAt = now.plusSeconds(study.intervalMinutes.toLong() * 60)
                study.lastError = null
                study.updatedAt = now
                log.info("scheduled_question_created deviceId={} userId={} studyId={} topic={} questionId={} pushOutbox=true", study.deviceId, userId, study.id, study.topic, saved.id)
            } catch (error: Exception) {
                study.lastError = error.message ?: error.javaClass.simpleName
                study.nextDueAt = now.plusSeconds(5 * 60)
                study.updatedAt = now
                log.warn("scheduled_question_failed deviceId={} userId={} studyId={} topic={} error={}", study.deviceId, study.userId, study.id, study.topic, error.message)
            }
        }
    }
}
