package com.buddystudy.backend.stats.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.stats.application.port.outbound.UserStatsOverview
import com.buddystudy.backend.stats.application.port.outbound.UserStatsPort
import com.buddystudy.stats.domain.entity.UserStatsEntity
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
class UserStatsRepository(
    private val template: R2dbcEntityTemplate,
    connectionFactory: ConnectionFactory,
) : UserStatsPort {
    private val postgres = connectionFactory.metadata.name.contains("PostgreSQL", ignoreCase = true)

    @Transactional
    override suspend fun replaceAll(rows: Collection<UserStatsEntity>) {
        template.delete(UserStatsEntity::class.java).all().awaitSingle()
        rows.forEach { template.saveEntity(it, 0) }
    }

    @Transactional
    override suspend fun syncAll(rows: Collection<UserStatsEntity>) {
        if (!postgres) {
            replaceAll(rows)
            return
        }
        if (rows.isEmpty()) {
            template.delete(UserStatsEntity::class.java).all().awaitSingle()
            return
        }
        val client = template.databaseClient
        client.sql(
            """
            create temporary table if not exists tmp_user_stats_sync (
                user_id bigint not null, stat_date date not null,
                topic_key varchar(255) not null, difficulty_level integer not null
            ) on commit drop
            """.trimIndent(),
        ).fetch().rowsUpdated().awaitSingle()
        client.sql("truncate table tmp_user_stats_sync").fetch().rowsUpdated().awaitSingle()
        rows.forEach { row ->
            client.sql(
                "insert into tmp_user_stats_sync (user_id, stat_date, topic_key, difficulty_level) values (:userId, :statDate, :topicKey, :difficultyLevel)",
            ).bind("userId", row.userId).bind("statDate", row.statDate).bind("topicKey", row.topicKey)
                .bind("difficultyLevel", row.difficultyLevel).fetch().rowsUpdated().awaitSingle()
            upsert(client, row)
        }
        client.sql(
            """
            delete from user_stats us where not exists (
                select 1 from tmp_user_stats_sync tmp
                where tmp.user_id = us.user_id and tmp.stat_date = us.stat_date
                  and tmp.topic_key = us.topic_key and tmp.difficulty_level = us.difficulty_level
            )
            """.trimIndent(),
        ).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun findByUser(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
    ): List<UserStatsEntity> = template.select(
        Query.query(criteria(userId, startDate, endDate, query)),
        UserStatsEntity::class.java,
    ).collectList().awaitSingle()

    override suspend fun overviewByUser(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
    ): UserStatsOverview {
        var spec = template.databaseClient.sql(
            """
            select coalesce(sum(response_count), 0) as total_responses,
                   count(distinct topic_key) as total_topics
            from user_stats ${whereSql(query)}
            """.trimIndent(),
        ).bind("userId", userId).bind("startDate", startDate ?: MIN_STATS_DATE).bind("endDate", endDate ?: MAX_STATS_DATE)
        if (!query.isNullOrBlank()) spec = spec.bind("query", "%${query.trim().lowercase()}%")
        return spec.map { row, _ ->
            UserStatsOverview(
                totalResponses = (row.get("total_responses") as Number).toInt(),
                totalTopics = (row.get("total_topics") as Number).toLong(),
            )
        }.one().awaitSingleOrNull() ?: UserStatsOverview(0, 0)
    }

    override suspend fun findTopicKeysByUser(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
        limit: Int,
        offset: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        var spec = template.databaseClient.sql(
            """
            select topic_key from user_stats ${whereSql(query)}
            group by topic_key order by sum(response_count) desc, topic_key asc
            limit :limit offset :offset
            """.trimIndent(),
        ).bind("userId", userId).bind("startDate", startDate ?: MIN_STATS_DATE).bind("endDate", endDate ?: MAX_STATS_DATE)
            .bind("limit", limit).bind("offset", offset)
        if (!query.isNullOrBlank()) spec = spec.bind("query", "%${query.trim().lowercase()}%")
        return spec.map { row, _ -> row.get("topic_key", String::class.java)!! }.all().collectList().awaitSingle()
    }

    override suspend fun findByUserAndTopicKeys(
        userId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        query: String?,
        topicKeys: Collection<String>,
    ): List<UserStatsEntity> {
        if (topicKeys.isEmpty()) return emptyList()
        return template.select(
            Query.query(criteria(userId, startDate, endDate, query).and("topic_key").`in`(topicKeys)),
            UserStatsEntity::class.java,
        ).collectList().awaitSingle()
    }

    private fun criteria(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): Criteria {
        var result = Criteria.where("user_id").`is`(userId)
            .and("stat_date").greaterThanOrEquals(startDate ?: MIN_STATS_DATE)
            .and("stat_date").lessThan(endDate ?: MAX_STATS_DATE)
        if (!query.isNullOrBlank()) {
            val pattern = "%${query.trim().lowercase()}%"
            result = result.and(Criteria.where("topic").like(pattern).ignoreCase(true).or("topic_key").like(pattern).ignoreCase(true))
        }
        return result
    }

    private fun whereSql(query: String?) = buildString {
        append("where user_id = :userId and stat_date >= :startDate and stat_date < :endDate")
        if (!query.isNullOrBlank()) append(" and (lower(topic) like :query or lower(topic_key) like :query)")
    }

    private suspend fun upsert(client: DatabaseClient, row: UserStatsEntity) {
        client.sql(UPSERT_SQL)
            .bind("userId", row.userId).bind("statDate", row.statDate).bind("topicKey", row.topicKey)
            .bind("topic", row.topic).bind("difficultyLevel", row.difficultyLevel)
            .bind("responseCount", row.responseCount).bind("scoreCount", row.scoreCount)
            .bind("scoreSum", row.scoreSum).bind("bestScore", row.bestScore).bind("correctCount", row.correctCount)
            .bind("latestAt", row.latestAt).bind("createdAt", row.createdAt).bind("updatedAt", row.updatedAt)
            .fetch().rowsUpdated().awaitSingle()
    }

    private companion object {
        val MIN_STATS_DATE: LocalDate = LocalDate.of(1970, 1, 1)
        val MAX_STATS_DATE: LocalDate = LocalDate.of(9999, 12, 31)
        val UPSERT_SQL = """
            insert into user_stats (
                user_id, stat_date, topic_key, topic, difficulty_level, response_count,
                score_count, score_sum, best_score, correct_count, latest_at, created_at, updated_at
            ) values (
                :userId, :statDate, :topicKey, :topic, :difficultyLevel, :responseCount,
                :scoreCount, :scoreSum, :bestScore, :correctCount, :latestAt, :createdAt, :updatedAt
            ) on conflict (user_id, stat_date, topic_key, difficulty_level) do update set
                topic = excluded.topic, response_count = excluded.response_count,
                score_count = excluded.score_count, score_sum = excluded.score_sum,
                best_score = excluded.best_score, correct_count = excluded.correct_count,
                latest_at = excluded.latest_at, updated_at = excluded.updated_at
        """.trimIndent()
    }
}
