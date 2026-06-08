package com.buddystuddy.backend.stats

import com.buddystuddy.backend.domain.QuestionEntity
import com.buddystuddy.backend.dto.StatsResponse
import com.buddystuddy.backend.dto.TopicLevelRangeResponse
import com.buddystuddy.backend.dto.TopicStatsResponse
import com.buddystuddy.backend.dto.toRecord
import com.buddystuddy.backend.study.repository.QuestionRepository
import com.buddystuddy.backend.study.repository.QuestionStatsRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.text.Normalizer
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

@Service
class StatsService(
    private val questions: QuestionRepository,
    private val stats: QuestionStatsRepository,
) {
    fun stats(userId: Long, limit: Int, offset: Int): StatsResponse {
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
            records = rows.take(20).map { it.toRecord(stats.findById(it.id).orElse(null)) },
        )
    }

    private fun normalizedTopic(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ")
}
