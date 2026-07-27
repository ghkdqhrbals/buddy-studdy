package com.buddystudy.backend.stats.adapter.outbound.persistence

import com.buddystudy.backend.stats.application.port.outbound.StudyGrowthRecord
import com.buddystudy.backend.stats.application.port.outbound.StudyGrowthStatsPort
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import io.r2dbc.spi.Row
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Repository
class StudyGrowthStatsRepository(
    private val template: R2dbcEntityTemplate,
) : StudyGrowthStatsPort {
    override suspend fun findByUser(
        userId: Long,
        startAt: Instant,
        endAt: Instant,
    ): List<StudyGrowthRecord> =
        template.databaseClient.sql(
            """
            select study_id, difficulty_level, score, answered_at, created_at
            from questions
            where user_id = :userId
              and deleted_at is null
              and study_id is not null
              and (
                (created_at >= :startAt and created_at < :endAt)
                or (answered_at >= :startAt and answered_at < :endAt)
              )
            order by coalesce(answered_at, created_at) asc, id asc
            """.trimIndent(),
        )
            .bind("userId", userId)
            .bind("startAt", startAt)
            .bind("endAt", endAt)
            .map { row, _ ->
                val createdAt = row.instant("created_at")
                val answeredAt = row.instantOrNull("answered_at")
                val score = (row.get("score") as Number?)?.toInt()
                StudyGrowthRecord(
                    studyId = (row.get("study_id") as Number).toLong(),
                    difficultyLevel = (row.get("difficulty_level") as Number).toInt(),
                    score = score ?: 0,
                    answeredAt = answeredAt ?: createdAt,
                    createdAt = createdAt,
                    completed = score != null && answeredAt != null,
                )
            }
            .all()
            .collectList()
            .awaitSingle()

    private fun Row.instant(name: String): Instant =
        instantOrNull(name) ?: error("Missing timestamp value for $name")

    private fun Row.instantOrNull(name: String): Instant? =
        when (val value = get(name)) {
            null -> null
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is ZonedDateTime -> value.toInstant()
            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            else -> error("Unsupported timestamp value for $name: ${value?.javaClass?.name}")
        }
}
