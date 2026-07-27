package com.buddystudy.backend.stats

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.stats.application.model.StudyGrowthNodeResponse
import com.buddystudy.backend.stats.application.model.StudyGrowthResponse
import com.buddystudy.backend.stats.application.model.StudyGrowthRootResponse
import com.buddystudy.backend.stats.application.port.inbound.GetStudyGrowthUseCase
import com.buddystudy.backend.stats.application.port.outbound.StudyGrowthRecord
import com.buddystudy.backend.stats.application.port.outbound.StudyGrowthStatsPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.StudyEntity
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.min

@Service
class StudyGrowthService(
    private val studies: StudyPort,
    private val growthStats: StudyGrowthStatsPort,
    private val assembler: StudyGrowthAssembler = StudyGrowthAssembler(),
) : GetStudyGrowthUseCase {
    override suspend fun growth(
        principal: Principal,
        startAt: Instant?,
        endAt: Instant?,
    ): StudyGrowthResponse {
        val now = Instant.now()
        val safeEndAt = endAt?.coerceAtMost(now) ?: now
        val requestedStartAt = startAt ?: safeEndAt.minus(DEFAULT_GROWTH_PERIOD)
        val safeStartAt = requestedStartAt.takeIf { it.isBefore(safeEndAt) }
            ?: safeEndAt.minus(DEFAULT_GROWTH_PERIOD)
        val userStudies = studies.findAllByUserId(principal.userId)
        val records = if (userStudies.isEmpty()) {
            emptyList()
        } else {
            growthStats.findByUser(principal.userId, safeStartAt, safeEndAt)
        }
        return assembler.assemble(
            studies = userStudies,
            records = records,
            startAt = safeStartAt,
            endAt = safeEndAt,
            generatedAt = now,
        )
    }

    private companion object {
        val DEFAULT_GROWTH_PERIOD: Duration = Duration.ofDays(90)
    }
}

class StudyGrowthAssembler {
    fun assemble(
        studies: List<StudyEntity>,
        records: List<StudyGrowthRecord>,
        startAt: Instant,
        endAt: Instant,
        generatedAt: Instant,
    ): StudyGrowthResponse {
        if (studies.isEmpty()) {
            return StudyGrowthResponse(
                roots = emptyList(),
                nodes = emptyList(),
                startAt = startAt,
                endAt = endAt,
                generatedAt = generatedAt,
            )
        }

        val byId = studies.associateBy { it.id }
        val childrenByParent = studies
            .filter { it.parentStudyId != null && byId.containsKey(it.parentStudyId) }
            .groupBy { checkNotNull(it.parentStudyId) }
            .mapValues { (_, children) -> children.sortedWith(studyOrdering) }
        val roots = studies
            .filter { it.parentStudyId == null || !byId.containsKey(it.parentStudyId) }
            .sortedWith(studyOrdering)
        val recordsByStudy = records
            .filter { byId.containsKey(it.studyId) }
            .groupBy { it.studyId }
            .mapValues { (_, samples) -> samples.sortedBy { it.answeredAt } }
        val directGrowthByStudy = studies.associate { study ->
            study.id to directGrowth(recordsByStudy[study.id].orEmpty())
        }
        val subtreeMemo = mutableMapOf<Long, Set<Long>>()

        fun subtreeIDs(studyId: Long, visiting: Set<Long> = emptySet()): Set<Long> {
            subtreeMemo[studyId]?.let { return it }
            if (studyId in visiting) return setOf(studyId)
            val nextVisiting = visiting + studyId
            val result = buildSet {
                add(studyId)
                childrenByParent[studyId].orEmpty().forEach { child ->
                    addAll(subtreeIDs(child.id, nextVisiting))
                }
            }
            subtreeMemo[studyId] = result
            return result
        }

        fun rootID(study: StudyEntity): Long {
            var current = study
            val visited = mutableSetOf<Long>()
            while (current.parentStudyId != null && visited.add(current.id)) {
                current = byId[current.parentStudyId] ?: break
            }
            return current.id
        }

        fun depth(study: StudyEntity): Int {
            var current = study
            var result = 0
            val visited = mutableSetOf<Long>()
            while (current.parentStudyId != null && visited.add(current.id)) {
                current = byId[current.parentStudyId] ?: break
                result += 1
            }
            return result
        }

        fun aggregate(studyId: Long): AggregateGrowth {
            val subtree = subtreeIDs(studyId)
            val direct = subtree.mapNotNull(directGrowthByStudy::get)
            val currentValues = direct.filter { it.currentLevel != null }
            val currentLevel = weightedAverage(currentValues) { checkNotNull(it.currentLevel) }
            val growthValues = direct.filter { it.growth != null }
            val growth = weightedAverage(growthValues) { checkNotNull(it.growth) }
            val samples = subtree
                .flatMap { recordsByStudy[it].orEmpty() }
                .sortedBy { it.answeredAt }
            return AggregateGrowth(
                currentLevel = currentLevel,
                previousLevel = if (currentLevel != null && growth != null) currentLevel - growth else null,
                growth = growth,
                answerCount = direct.sumOf { it.answerCount },
                measuredTopicCount = growthValues.size,
                totalTopicCount = subtree.size,
                latestAt = direct.mapNotNull { it.latestAt }.maxOrNull(),
                trend = trend(samples),
            )
        }

        val nodes = studies
            .sortedWith(
                compareBy<StudyEntity>({ rootID(it) }, { depth(it) }, { it.sortOrder }, { it.id }),
            )
            .map { study ->
                val aggregate = aggregate(study.id)
                StudyGrowthNodeResponse(
                    studyId = study.id,
                    parentStudyId = study.parentStudyId?.takeIf(byId::containsKey),
                    rootStudyId = rootID(study),
                    topic = study.topic,
                    sortOrder = study.sortOrder,
                    depth = depth(study),
                    childCount = childrenByParent[study.id].orEmpty().size,
                    activeForQuestions = study.activeForQuestions,
                    currentLevel = aggregate.currentLevel,
                    previousLevel = aggregate.previousLevel,
                    growth = aggregate.growth,
                    answerCount = aggregate.answerCount,
                    measuredTopicCount = aggregate.measuredTopicCount,
                    totalTopicCount = aggregate.totalTopicCount,
                    latestAt = aggregate.latestAt,
                    trend = aggregate.trend,
                )
            }
        val nodesByID = nodes.associateBy { it.studyId }
        val rootResponses = roots.mapNotNull { root ->
            nodesByID[root.id]?.let { node ->
                StudyGrowthRootResponse(
                    studyId = node.studyId,
                    topic = node.topic,
                    activeForQuestions = node.activeForQuestions,
                    currentLevel = node.currentLevel,
                    previousLevel = node.previousLevel,
                    growth = node.growth,
                    answerCount = node.answerCount,
                    measuredTopicCount = node.measuredTopicCount,
                    totalTopicCount = node.totalTopicCount,
                    trend = node.trend,
                )
            }
        }
        return StudyGrowthResponse(
            roots = rootResponses,
            nodes = nodes,
            startAt = startAt,
            endAt = endAt,
            generatedAt = generatedAt,
        )
    }

    private fun directGrowth(records: List<StudyGrowthRecord>): DirectGrowth {
        if (records.isEmpty()) return DirectGrowth()
        val recentSize = min(MAX_WINDOW_SIZE, records.size)
        val recent = records.takeLast(recentSize)
        val current = recent.map(::estimatedLevel).average()
        if (records.size < MIN_MEASURED_RESPONSES) {
            return DirectGrowth(
                currentLevel = current,
                answerCount = records.size,
                latestAt = records.last().answeredAt,
            )
        }
        val windowSize = min(MAX_WINDOW_SIZE, records.size / 2)
        val previous = records.dropLast(windowSize).takeLast(windowSize)
        val latest = records.takeLast(windowSize)
        val previousLevel = previous.map(::estimatedLevel).average()
        val currentLevel = latest.map(::estimatedLevel).average()
        return DirectGrowth(
            currentLevel = currentLevel,
            previousLevel = previousLevel,
            growth = currentLevel - previousLevel,
            answerCount = records.size,
            latestAt = records.last().answeredAt,
        )
    }

    private fun weightedAverage(
        values: List<DirectGrowth>,
        value: (DirectGrowth) -> Double,
    ): Double? {
        if (values.isEmpty()) return null
        val weightSum = values.sumOf { min(it.answerCount, MAX_NODE_WEIGHT) }
        if (weightSum <= 0) return null
        return values.sumOf { value(it) * min(it.answerCount, MAX_NODE_WEIGHT).toDouble() } /
            weightSum.toDouble()
    }

    private fun trend(records: List<StudyGrowthRecord>): List<Double> {
        if (records.size < 2) return records.map(::estimatedLevel)
        val chunkSize = ceil(records.size.toDouble() / MAX_TREND_POINTS.toDouble()).toInt().coerceAtLeast(1)
        return records
            .chunked(chunkSize)
            .map { chunk -> chunk.map(::estimatedLevel).average() }
            .takeLast(MAX_TREND_POINTS)
    }

    private fun estimatedLevel(record: StudyGrowthRecord): Double =
        (
            record.difficultyLevel.toDouble() +
                ((record.score.coerceIn(0, 100).toDouble() - 50.0) / 30.0)
            ).coerceIn(1.0, 10.0)

    private data class DirectGrowth(
        val currentLevel: Double? = null,
        val previousLevel: Double? = null,
        val growth: Double? = null,
        val answerCount: Int = 0,
        val latestAt: Instant? = null,
    )

    private data class AggregateGrowth(
        val currentLevel: Double?,
        val previousLevel: Double?,
        val growth: Double?,
        val answerCount: Int,
        val measuredTopicCount: Int,
        val totalTopicCount: Int,
        val latestAt: Instant?,
        val trend: List<Double>,
    )

    private companion object {
        const val MIN_MEASURED_RESPONSES = 6
        const val MAX_WINDOW_SIZE = 5
        const val MAX_NODE_WEIGHT = 5
        const val MAX_TREND_POINTS = 6
        val studyOrdering = compareBy<StudyEntity>({ it.sortOrder }, { it.id })
    }
}
