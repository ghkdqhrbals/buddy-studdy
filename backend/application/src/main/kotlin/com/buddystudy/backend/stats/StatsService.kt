package com.buddystudy.backend.stats

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.stats.application.model.StatsActivityDayResponse
import com.buddystudy.backend.stats.application.model.StatsActivityResponse
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.model.StatsResponse
import com.buddystudy.backend.stats.application.model.TopicLevelRangeResponse
import com.buddystudy.backend.stats.application.model.TopicStatsResponse
import com.buddystudy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystudy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import com.buddystudy.backend.stats.application.port.outbound.UserStatsPort
import com.buddystudy.backend.study.application.model.StudyRecordResponse
import com.buddystudy.backend.study.application.model.toRecordResponse
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.stats.domain.entity.UserStatsEntity
import com.buddystudy.study.domain.StudyRecord
import com.buddystudy.study.domain.StudyRecordState
import com.buddystudy.study.domain.StudyRecordStats
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Service
class StatsService(
    private val userStats: UserStatsPort,
    private val questions: QuestionPort,
    private val stats: QuestionStatsPort,
    private val topicStatsAssembler: TopicStatsAssembler = TopicStatsAssembler(),
) : GetStudyStatsUseCase {
    override suspend fun stats(principal: Principal, limit: Int, offset: Int, query: StatsQuery): StatsResponse =
        stats(principal.userId, limit, offset, query)

    override suspend fun activity(principal: Principal, startAt: Instant?, endAt: Instant?): StatsActivityResponse {
        val now = Instant.now()
        val today = LocalDate.now(ZoneOffset.UTC)
        val startDate = startAt?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: today.minusDays(364)
        val exclusiveEndDate = endAt?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: today.plusDays(1)
        val safeStartDate = minOf(startDate, exclusiveEndDate.minusDays(1))
        val rows = userStats.findByUser(principal.userId, safeStartDate, exclusiveEndDate, null)
        val rowsByDate = rows.groupBy { it.statDate }
        val days = generateSequence(safeStartDate) { current ->
            current.plusDays(1).takeIf { it.isBefore(exclusiveEndDate) }
        }.map { date ->
            val dayRows = rowsByDate[date].orEmpty()
            StatsActivityDayResponse(
                date = date,
                answerCount = dayRows.sumOf { it.responseCount },
                topicCount = dayRows.map { it.topicKey }.distinct().size,
                topics = dayRows
                    .sortedByDescending { it.responseCount }
                    .map { it.topic }
                    .distinct()
                    .take(4),
                bestLevel = dayRows
                    .filter { it.scoreCount > 0 }
                    .maxOfOrNull { row ->
                        estimatedLevel(row.difficultyLevel, row.scoreSum.toDouble() / row.scoreCount.toDouble())
                    },
            )
        }.toList()
        val countsByDate = days.associate { it.date to it.answerCount }
        val streakDays = generateSequence(today) { it.minusDays(1) }
            .takeWhile { (countsByDate[it] ?: 0) > 0 }
            .count()
        val monthStart = today.withDayOfMonth(1)
        val monthAnswerCount = days
            .asSequence()
            .filter { !it.date.isBefore(monthStart) && !it.date.isAfter(today) }
            .sumOf { it.answerCount }
        return StatsActivityResponse(
            days = days,
            streakDays = streakDays,
            monthAnswerCount = monthAnswerCount,
            generatedAt = now,
        )
    }

    private suspend fun stats(userId: Long, limit: Int, offset: Int, query: StatsQuery): StatsResponse {
        val bounds = query.dateBounds()
        val search = query.search?.trim()?.takeIf { it.isNotEmpty() }
        val overview = userStats.overviewByUser(userId, bounds.startDate, bounds.endDate, search)
        val selectedTopicKeys = userStats.findTopicKeysByUser(userId, bounds.startDate, bounds.endDate, search, limit, offset)
        if (selectedTopicKeys.isEmpty()) {
            return StatsResponse(
                totalResponses = overview.totalResponses,
                totalTopics = overview.totalTopics.toInt(),
                topics = emptyList(),
                totalCount = overview.totalTopics,
                limit = limit,
                offset = offset,
                generatedAt = Instant.now(),
            )
        }
        val grouped = userStats.findByUserAndTopicKeys(userId, bounds.startDate, bounds.endDate, search, selectedTopicKeys)
            .groupBy { it.topicKey }
        val selectedGroups = selectedTopicKeys.mapNotNull { grouped[it] }
        val latestRecordsByTopicKey = latestRecordsByTopicKey(userId, selectedGroups)
        val topics = selectedGroups.map { topicRows ->
            topicStatsAssembler.assemble(topicRows, latestRecordsByTopicKey[topicRows.first().topicKey].orEmpty())
        }
        return StatsResponse(
            totalResponses = overview.totalResponses,
            totalTopics = overview.totalTopics.toInt(),
            topics = topics,
            totalCount = overview.totalTopics,
            limit = limit,
            offset = offset,
            generatedAt = Instant.now(),
        )
    }

    private suspend fun latestRecordsByTopicKey(
        userId: Long,
        topicGroups: List<List<UserStatsEntity>>,
    ): Map<String, List<StudyRecordResponse>> {
        if (topicGroups.isEmpty()) return emptyMap()
        val aliases = topicGroups.flatMap { rows ->
            rows.sortedByDescending { it.responseCount }.map { it.topic }.distinct()
        }.distinct()
        if (aliases.isEmpty()) return emptyMap()
        val records = questions.findLatestGradedByUserAndTopics(
            userId,
            aliases,
            perTopicLimit = 20,
        )
        val statsByQuestionId = records
            .map { it.id }
            .takeIf { it.isNotEmpty() }
            ?.let { stats.findAllByIds(it).associateBy { stat -> stat.questionId } }
            .orEmpty()
        return records
            .groupBy { normalizedTopic(it.topic) }
            .mapValues { (_, topicRecords) ->
                topicRecords
                    .sortedByDescending { it.answeredAt ?: it.createdAt }
                    .take(20)
                    .map { it.toStudyRecord(statsByQuestionId[it.id]).toProjection().toRecordResponse() }
            }
    }

    private fun QuestionEntity.toStudyRecord(statsEntity: QuestionStatsEntity?) = StudyRecord.of(
        StudyRecordState(
            id = id,
            question = question,
            hint = hint,
            createdAt = createdAt,
            answer = answer,
            score = score,
            correct = correct,
            feedback = feedback,
            explanation = explanation,
            topic = topic,
            difficultyLevel = difficultyLevel,
            answeredAt = answeredAt,
            publicQuestion = publicQuestion,
            studyId = studyId,
        ),
        statsEntity?.let { StudyRecordStats(it.likeCount, it.commentCount, it.viewCount) },
    )

}

private fun estimatedLevel(difficultyLevel: Int, score: Double): Double =
    (difficultyLevel.toDouble() + ((score.coerceIn(0.0, 100.0) - 50.0) / 30.0)).coerceIn(1.0, 10.0)

class TopicStatsAssembler {
    fun assemble(rows: List<UserStatsEntity>, records: List<StudyRecordResponse>): TopicStatsResponse {
        require(rows.isNotEmpty()) { "Topic stats rows must not be empty." }
        val responseCount = rows.sumOf { it.responseCount }
        val scoreCount = rows.sumOf { it.scoreCount }
        val scoreSum = rows.sumOf { it.scoreSum }
        val avg = if (scoreCount == 0) 0 else scoreSum / scoreCount
        val best = rows.maxOfOrNull { it.bestScore } ?: 0
        val correctRate = if (scoreCount == 0) 0 else rows.sumOf { it.correctCount } * 100 / scoreCount
        val estimates = rows
            .filter { it.scoreCount > 0 }
            .map { row ->
                val rowAverage = row.scoreSum.toDouble() / row.scoreCount.toDouble()
                LevelEstimate(value = estimatedLevel(row.difficultyLevel, rowAverage), weight = row.scoreCount)
            }
        val center = weightedCenter(estimates) ?: rows.first().difficultyLevel.toDouble()
        val level = center.roundToInt().coerceIn(1, 10)
        val uncertainty = uncertainty(estimates, center, scoreCount)
        val topicRows = rows.sortedByDescending { it.responseCount }
        val aliases = topicRows.map { it.topic }.distinct()
        return TopicStatsResponse(
            topicKey = rows.first().topicKey,
            topic = aliases.firstOrNull() ?: rows.first().topic,
            topicAliases = aliases,
            count = responseCount,
            average = avg,
            best = best,
            correctRate = correctRate,
            levelRange = TopicLevelRangeResponse(
                level = level,
                average = avg,
                sampleCount = scoreCount,
                centerLevel = center.coerceIn(1.0, 10.0),
                lowerBound = (center - uncertainty).coerceIn(1.0, 10.0),
                upperBound = (center + uncertainty).coerceIn(1.0, 10.0),
            ),
            latestAt = rows.maxOf { it.latestAt },
            records = records,
        )
    }

    private fun weightedCenter(estimates: List<LevelEstimate>): Double? {
        val weightSum = estimates.sumOf { it.weight }
        if (weightSum <= 0) return null
        return estimates.sumOf { it.value * it.weight.toDouble() } / weightSum.toDouble()
    }

    private fun uncertainty(estimates: List<LevelEstimate>, center: Double, sampleCount: Int): Double {
        val weightSum = estimates.sumOf { it.weight }
        val variance = if (weightSum > 1) {
            estimates.sumOf { it.weight.toDouble() * (it.value - center).pow(2) } / weightSum.toDouble()
        } else {
            0.0
        }
        val sampleUncertainty = 0.9 / sqrt(max(sampleCount, 1).toDouble())
        val conflictUncertainty = sqrt(variance) * 0.55
        return minOf(4.0, max(minimumHalfWidth(sampleCount), sampleUncertainty + conflictUncertainty))
    }

    private fun minimumHalfWidth(sampleCount: Int): Double =
        when {
            sampleCount >= 8 -> 0.3
            sampleCount >= 4 -> 0.45
            else -> 0.65
        }

    private data class LevelEstimate(
        val value: Double,
        val weight: Int,
    )
}

@Service
class StatsRefreshService(
    private val questions: QuestionPort,
    private val userStats: UserStatsPort,
    private val rowBuilder: UserStatsRowBuilder = UserStatsRowBuilder(),
) : RefreshUserStatsUseCase {
    override suspend fun refreshAll(now: Instant) {
        val rows = rowBuilder.build(questions.findAllGradedForStats(PageRequest.of(0, MAX_REFRESH_QUESTIONS)).content, now)
        userStats.syncAll(rows)
    }
}

class UserStatsRowBuilder {
    fun build(questions: List<QuestionEntity>, now: Instant): List<UserStatsEntity> =
        questions
            .filter { it.userId != null && it.deletedAt == null && it.score != null }
            .groupBy { StatsBucketKey(it.userId!!, statsDate(it), normalizedTopic(it.topic), it.difficultyLevel) }
            .map { (key, rows) -> key.toEntity(rows, now) }

    private fun StatsBucketKey.toEntity(rows: List<QuestionEntity>, now: Instant): UserStatsEntity {
        val scores = rows.mapNotNull { it.score }
        return UserStatsEntity(
            userId = userId,
            statDate = statDate,
            topicKey = topicKey,
            topic = rows.groupingBy { it.topic }.eachCount().maxByOrNull { it.value }?.key ?: rows.first().topic,
            difficultyLevel = difficultyLevel,
            responseCount = rows.size,
            scoreCount = scores.size,
            scoreSum = scores.sum(),
            bestScore = scores.maxOrNull() ?: 0,
            correctCount = rows.count { it.correct == true || (it.score ?: 0) >= 70 },
            latestAt = rows.maxOf { it.answeredAt ?: it.createdAt },
            createdAt = now,
            updatedAt = now,
        )
    }

    private data class StatsBucketKey(
        val userId: Long,
        val statDate: LocalDate,
        val topicKey: String,
        val difficultyLevel: Int,
    )
}

private const val MAX_REFRESH_QUESTIONS = 500_000

internal fun normalizedTopic(value: String): String =
    Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFKC).replace(Regex("\\s+"), " ")

internal fun statsDate(question: QuestionEntity): LocalDate =
    (question.answeredAt ?: question.createdAt).atZone(ZoneOffset.UTC).toLocalDate()

private data class DateBounds(val startDate: LocalDate?, val endDate: LocalDate?)

private fun StatsQuery.dateBounds(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): DateBounds {
    val explicitStart = startAt?.atZone(ZoneOffset.UTC)?.toLocalDate()
    val explicitEnd = endAt?.atZone(ZoneOffset.UTC)?.toLocalDate()
    if (explicitStart != null || explicitEnd != null) {
        return DateBounds(explicitStart, explicitEnd)
    }
    return when (period?.lowercase()) {
        "today" -> DateBounds(today, today.plusDays(1))
        "last7" -> DateBounds(today.minusDays(6), today.plusDays(1))
        "last30" -> DateBounds(today.minusDays(29), today.plusDays(1))
        "last90" -> DateBounds(today.minusDays(89), today.plusDays(1))
        else -> DateBounds(null, null)
    }
}
