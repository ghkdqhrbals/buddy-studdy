package com.buddystuddy.backend.stats

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.stats.application.model.StatsQuery
import com.buddystuddy.backend.stats.application.model.StatsResponse
import com.buddystuddy.backend.stats.application.model.TopicLevelRangeResponse
import com.buddystuddy.backend.stats.application.model.TopicStatsResponse
import com.buddystuddy.backend.stats.application.port.inbound.GetStudyStatsUseCase
import com.buddystuddy.backend.stats.application.port.inbound.RefreshUserStatsUseCase
import com.buddystuddy.backend.stats.application.port.outbound.UserStatsPort
import com.buddystuddy.backend.study.application.model.StudyRecordResponse
import com.buddystuddy.backend.study.application.model.toRecordResponse
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.stats.domain.entity.UserStatsEntity
import com.buddystuddy.study.domain.StudyRecord
import com.buddystuddy.study.domain.StudyRecordState
import com.buddystuddy.study.domain.StudyRecordStats
import com.buddystuddy.study.domain.entity.QuestionEntity
import com.buddystuddy.study.domain.entity.QuestionStatsEntity
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max

@Service
class StatsService(
    private val userStats: UserStatsPort,
    private val questions: QuestionPort,
    private val stats: QuestionStatsPort,
    private val topicStatsAssembler: TopicStatsAssembler = TopicStatsAssembler(),
) : GetStudyStatsUseCase {
    override fun stats(principal: Principal, limit: Int, offset: Int, query: StatsQuery): StatsResponse =
        stats(principal.userId, limit, offset, query)

    private fun stats(userId: Long, limit: Int, offset: Int, query: StatsQuery): StatsResponse {
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

    private fun latestRecordsByTopicKey(
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
        ),
        statsEntity?.let { StudyRecordStats(it.likeCount, it.commentCount, it.viewCount) },
    )

}

class TopicStatsAssembler {
    fun assemble(rows: List<UserStatsEntity>, records: List<StudyRecordResponse>): TopicStatsResponse {
        require(rows.isNotEmpty()) { "Topic stats rows must not be empty." }
        val responseCount = rows.sumOf { it.responseCount }
        val scoreCount = rows.sumOf { it.scoreCount }
        val scoreSum = rows.sumOf { it.scoreSum }
        val avg = if (scoreCount == 0) 0 else scoreSum / scoreCount
        val best = rows.maxOfOrNull { it.bestScore } ?: 0
        val correctRate = if (scoreCount == 0) 0 else rows.sumOf { it.correctCount } * 100 / scoreCount
        val dominantLevelRows = rows.groupBy { it.difficultyLevel }.maxByOrNull { it.value.sumOf(UserStatsEntity::responseCount) }
        val level = dominantLevelRows?.key ?: rows.first().difficultyLevel
        val center = level + ((avg - 50) / 100.0)
        val uncertainty = 1.6 / max(1.0, scoreCount.toDouble()).coerceAtMost(4.0)
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
}

@Service
class StatsRefreshService(
    private val questions: QuestionPort,
    private val userStats: UserStatsPort,
    private val rowBuilder: UserStatsRowBuilder = UserStatsRowBuilder(),
) : RefreshUserStatsUseCase {
    override fun refreshAll(now: Instant) {
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
