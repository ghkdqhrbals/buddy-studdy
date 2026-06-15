package com.buddystuddy.backend.stats.adapter.outbound.persistence

import com.buddystuddy.backend.stats.application.port.outbound.UserStatsPort
import com.buddystuddy.stats.domain.entity.UserStatsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import javax.sql.DataSource

@Repository
interface UserStatsJpaRepository : JpaRepository<UserStatsEntity, Long> {
    @Query(
        """
        select s from UserStatsEntity s
        where s.userId = :userId
          and s.statDate >= :startDate
          and s.statDate < :endDate
        """
    )
    fun findStatsRows(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
    ): List<UserStatsEntity>

    @Query(
        """
        select s from UserStatsEntity s
        where s.userId = :userId
          and s.statDate >= :startDate
          and s.statDate < :endDate
          and (
            lower(s.topic) like concat('%', lower(:query), '%')
            or lower(s.topicKey) like concat('%', lower(:query), '%')
          )
        """
    )
    fun findStatsRowsByQuery(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        @Param("query") query: String,
    ): List<UserStatsEntity>
}

@Repository
class UserStatsRepository(
    private val jpa: UserStatsJpaRepository,
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
) : UserStatsPort {
    @Transactional
    override fun replaceAll(rows: Collection<UserStatsEntity>) {
        jpa.deleteAllInBatch()
        jpa.saveAll(rows)
    }

    @Transactional
    override fun syncAll(rows: Collection<UserStatsEntity>) {
        if (!isPostgreSQL()) {
            replaceAll(rows)
            return
        }
        if (rows.isEmpty()) {
            jpa.deleteAllInBatch()
            return
        }
        val rowsToSync = rows.toList()

        jdbc.execute(
            """
            create temporary table if not exists tmp_user_stats_sync (
                user_id bigint not null,
                stat_date date not null,
                topic_key varchar(255) not null,
                difficulty_level integer not null
            ) on commit drop
            """.trimIndent(),
        )
        jdbc.update("truncate table tmp_user_stats_sync")
        jdbc.batchUpdate(
            """
            insert into tmp_user_stats_sync (user_id, stat_date, topic_key, difficulty_level)
            values (?, ?, ?, ?)
            """.trimIndent(),
            rowsToSync.keysBatchSetter(),
        )
        jdbc.batchUpdate(UPSERT_SQL, rowsToSync.upsertBatchSetter())
        jdbc.update(
            """
            delete from user_stats us
            where not exists (
                select 1
                from tmp_user_stats_sync tmp
                where tmp.user_id = us.user_id
                  and tmp.stat_date = us.stat_date
                  and tmp.topic_key = us.topic_key
                  and tmp.difficulty_level = us.difficulty_level
            )
            """.trimIndent(),
        )
    }

    override fun findByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): List<UserStatsEntity> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedStartDate = startDate ?: MIN_STATS_DATE
        val normalizedEndDate = endDate ?: MAX_STATS_DATE
        return if (normalizedQuery == null) {
            jpa.findStatsRows(userId, normalizedStartDate, normalizedEndDate)
        } else {
            jpa.findStatsRowsByQuery(userId, normalizedStartDate, normalizedEndDate, normalizedQuery)
        }
    }

    private companion object {
        val MIN_STATS_DATE: LocalDate = LocalDate.of(1970, 1, 1)
        val MAX_STATS_DATE: LocalDate = LocalDate.of(9999, 12, 31)
        val UPSERT_SQL = """
            insert into user_stats (
                user_id,
                stat_date,
                topic_key,
                topic,
                difficulty_level,
                response_count,
                score_count,
                score_sum,
                best_score,
                correct_count,
                latest_at,
                created_at,
                updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, stat_date, topic_key, difficulty_level)
            do update set
                topic = excluded.topic,
                response_count = excluded.response_count,
                score_count = excluded.score_count,
                score_sum = excluded.score_sum,
                best_score = excluded.best_score,
                correct_count = excluded.correct_count,
                latest_at = excluded.latest_at,
                updated_at = excluded.updated_at
        """.trimIndent()
    }

    private fun isPostgreSQL(): Boolean =
        dataSource.connection.use { connection ->
            connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
        }

    private fun List<UserStatsEntity>.keysBatchSetter(): BatchPreparedStatementSetter =
        object : BatchPreparedStatementSetter {
            override fun getBatchSize(): Int = size

            override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                val row = this@keysBatchSetter[i]
                ps.setLong(1, row.userId)
                ps.setObject(2, row.statDate)
                ps.setString(3, row.topicKey)
                ps.setInt(4, row.difficultyLevel)
            }
        }

    private fun List<UserStatsEntity>.upsertBatchSetter(): BatchPreparedStatementSetter =
        object : BatchPreparedStatementSetter {
            override fun getBatchSize(): Int = size

            override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
                val row = this@upsertBatchSetter[i]
                ps.setLong(1, row.userId)
                ps.setObject(2, row.statDate)
                ps.setString(3, row.topicKey)
                ps.setString(4, row.topic)
                ps.setInt(5, row.difficultyLevel)
                ps.setInt(6, row.responseCount)
                ps.setInt(7, row.scoreCount)
                ps.setInt(8, row.scoreSum)
                ps.setInt(9, row.bestScore)
                ps.setInt(10, row.correctCount)
                ps.setObject(11, row.latestAt)
                ps.setObject(12, row.createdAt)
                ps.setObject(13, row.updatedAt)
            }
        }
}
