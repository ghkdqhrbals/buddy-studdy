package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.admin.application.model.APIStatusResponse
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.settings.application.model.toSettings
import com.buddystuddy.backend.stats.application.model.StatsResponse
import com.buddystuddy.backend.stats.application.model.TopicLevelRangeResponse
import com.buddystuddy.backend.stats.application.model.TopicStatsResponse
import com.buddystuddy.backend.study.application.model.BackendSnapshotResponse
import com.buddystuddy.backend.study.application.port.inbound.SnapshotUseCase
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import com.buddystuddy.backend.study.domain.StudyQuestionAggregate
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.time.Instant
import kotlin.math.max

@Service
class SnapshotService(
    private val properties: BuddyStuddyProperties,
    private val schedules: SchedulePort,
    private val questions: QuestionPort,
    private val questionStats: QuestionStatsPort,
) : SnapshotUseCase {
    @Transactional(readOnly = true)
    override fun snapshot(principal: Principal, limit: Int, offset: Int): BackendSnapshotResponse {
        val schedule = schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
        val recordsPage = questions.findVisibleByUser(principal.userId, includePending = false, PageRequest.of(offset / limit, limit))
        return BackendSnapshotResponse(
            settings = schedule.toSettings(),
            api = APIStatusResponse(!schedule?.openaiApiKeyCipher.isNullOrBlank(), schedule?.openaiModel ?: properties.openai.model),
            records = recordsPage.content.map { StudyQuestionAggregate.of(it, questionStats.findById(it.id).orElse(null)).snapshot().toRecordResponse() },
            stats = stats(principal.userId, limit = 8, offset = 0),
            totalCount = recordsPage.totalElements,
            serverTime = Instant.now(),
        )
    }

    private fun stats(userId: Long, limit: Int, offset: Int): StatsResponse {
        val page = questions.findGradedByUser(userId, PageRequest.of(0, 10_000))
        val grouped = page.content.groupBy { normalizedTopic(it.topic) }
        val topics = grouped.values
            .sortedByDescending { it.size }
            .drop(offset)
            .take(limit)
            .map { rows -> topicStats(rows) }
        return StatsResponse(
            totalResponses = page.content.size,
            totalTopics = grouped.size,
            topics = topics,
            limit = limit,
            offset = offset,
            generatedAt = Instant.now(),
        )
    }

    private fun topicStats(rows: List<QuestionEntity>): TopicStatsResponse {
        val scored = rows.filter { it.score != null }
        val avg = scored.mapNotNull { it.score }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        val best = scored.mapNotNull { it.score }.maxOrNull() ?: 0
        val correctRate = if (scored.isEmpty()) 0 else (scored.count { it.correct == true || (it.score ?: 0) >= 70 } * 100 / scored.size)
        val byLevel = scored.groupBy { it.difficultyLevel }.maxByOrNull { it.value.size }
        val level = byLevel?.key ?: rows.first().difficultyLevel
        val center = level + ((avg - 50) / 100.0)
        val uncertainty = 1.6 / max(1.0, scored.size.toDouble()).coerceAtMost(4.0)
        return TopicStatsResponse(
            topicKey = normalizedTopic(rows.first().topic),
            topic = rows.first().topic,
            topicAliases = rows.map { it.topic }.distinct(),
            count = rows.size,
            average = avg,
            best = best,
            correctRate = correctRate,
            levelRange = TopicLevelRangeResponse(
                level = level,
                average = avg,
                sampleCount = scored.size,
                centerLevel = center.coerceIn(1.0, 10.0),
                lowerBound = (center - uncertainty).coerceIn(1.0, 10.0),
                upperBound = (center + uncertainty).coerceIn(1.0, 10.0),
            ),
            latestAt = rows.maxOf { it.createdAt },
            records = rows.take(20).map { StudyQuestionAggregate.of(it, questionStats.findById(it.id).orElse(null)).snapshot().toRecordResponse() },
        )
    }

    private fun normalizedTopic(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ")
}
