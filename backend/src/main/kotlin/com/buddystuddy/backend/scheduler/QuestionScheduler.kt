package com.buddystuddy.backend.scheduler

import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.domain.QuestionRepository
import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.domain.QuestionStatsRepository
import com.buddystuddy.backend.domain.ScheduleRepository
import com.buddystuddy.backend.openai.OpenAIClient
import com.buddystuddy.backend.stream.RedisStreamCoordinatorService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class QuestionScheduler(
    private val properties: BuddyStuddyProperties,
    private val schedules: ScheduleRepository,
    private val questions: QuestionRepository,
    private val questionStats: QuestionStatsRepository,
    private val cipher: KeyCipher,
    private val openAI: OpenAIClient,
    private val streams: RedisStreamCoordinatorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${buddystuddy.scheduler.poll-ms:30000}")
    @Transactional
    fun runScheduled() {
        if (!properties.scheduler.enabled) return
        val now = Instant.now()
        schedules.findDue(now, PageRequest.of(0, 50)).forEach { schedule ->
            try {
                val userId = schedule.userId
                val pending = questions.countPendingForStudy(schedule.deviceId, userId, schedule.topic)
                if (pending >= properties.scheduler.maxPendingPerStudy) {
                    schedule.lastError = "Pending question limit reached ($pending)."
                    schedule.nextDueAt = now.plusSeconds(5 * 60)
                    schedule.updatedAt = now
                    log.info("scheduled_question_skipped_pending deviceId={} userId={} topic={} pending={}", schedule.deviceId, userId, schedule.topic, pending)
                    return@forEach
                }
                val apiKey = cipher.decrypt(schedule.openaiApiKeyCipher) ?: properties.openai.apiKey
                if (apiKey.isBlank()) {
                    schedule.lastError = "No OpenAI API key configured for schedule."
                    schedule.nextDueAt = now.plusSeconds(5 * 60)
                    schedule.updatedAt = now
                    return@forEach
                }
                val recent = questions.findVisibleByUser(userId ?: -1, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
                val generated = openAI.generateQuestion(apiKey, schedule.openaiModel, schedule.topic, schedule.difficultyLevel, schedule.appLanguage, schedule.customPrompt, recent)
                val saved = questions.save(
                    QuestionEntity(
                        deviceId = schedule.deviceId,
                        userId = userId,
                        question = generated.question,
                        hint = generated.hint,
                        topic = schedule.topic,
                        difficultyLevel = schedule.difficultyLevel,
                        scheduledFor = schedule.nextDueAt ?: now,
                        sentAt = now,
                        status = "ungraded",
                        source = "scheduled",
                        publicQuestion = schedule.questionPublic,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                questionStats.save(QuestionStatsEntity(questionId = saved.id, updatedAt = now))
                val published = streams.publishPush(
                    mapOf(
                        "recordId" to saved.id,
                        "createdAt" to now.toString(),
                        "deviceId" to schedule.deviceId,
                        "userId" to userId,
                        "question" to generated.question,
                        "expectedAnswerHint" to generated.hint,
                        "topic" to schedule.topic,
                        "difficultyLevel" to schedule.difficultyLevel,
                        "language" to schedule.appLanguage,
                        "sound" to schedule.notificationSound,
                        "intervalMinutes" to schedule.intervalMinutes,
                    )
                )
                schedule.lastSentAt = now
                schedule.nextDueAt = now.plusSeconds(schedule.intervalMinutes.toLong() * 60)
                schedule.lastError = if (published) null else "Push stream publish failed."
                schedule.updatedAt = now
                log.info("scheduled_question_created deviceId={} userId={} topic={} questionId={} streamPublished={}", schedule.deviceId, userId, schedule.topic, saved.id, published)
            } catch (error: Exception) {
                schedule.lastError = error.message ?: error.javaClass.simpleName
                schedule.nextDueAt = now.plusSeconds(5 * 60)
                schedule.updatedAt = now
                log.warn("scheduled_question_failed deviceId={} userId={} topic={} error={}", schedule.deviceId, schedule.userId, schedule.topic, error.message)
            }
        }
    }
}
