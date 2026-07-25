package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

interface QuestionStatsRepository : CoroutineCrudRepository<QuestionStatsEntity, Long> {
    @Modifying
    @Query(
        """
        update question_stats
           set view_count = greatest(view_count + :delta, 0), updated_at = :now
         where question_id = :questionId
        """
    )
    suspend fun incrementView(@Param("questionId") questionId: Long, @Param("delta") delta: Int, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        update question_stats
           set like_count = greatest(like_count + :delta, 0), updated_at = :now
         where question_id = :questionId
        """
    )
    suspend fun incrementLike(@Param("questionId") questionId: Long, @Param("delta") delta: Int, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        update question_stats
           set comment_count = greatest(comment_count + :delta, 0), updated_at = :now
         where question_id = :questionId
        """
    )
    suspend fun incrementComment(@Param("questionId") questionId: Long, @Param("delta") delta: Int, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        update question_stats set like_count = :count, updated_at = :now
         where question_id = :questionId
        """
    )
    suspend fun setLikeCount(@Param("questionId") questionId: Long, @Param("count") count: Int, @Param("now") now: Instant): Int
}

@Component
class QuestionStatsPersistenceAdapter(
    private val repository: QuestionStatsRepository,
    private val databaseClient: DatabaseClient,
) : QuestionStatsPort {
    override suspend fun save(entity: QuestionStatsEntity): QuestionStatsEntity {
        var statement = databaseClient.sql(
            """
            insert into question_stats (
                question_id, like_count, comment_count, view_count, verified_at, updated_at
            ) values (
                :questionId, :likeCount, :commentCount, :viewCount, :verifiedAt, :updatedAt
            )
            on duplicate key update
                like_count = values(like_count),
                comment_count = values(comment_count),
                view_count = values(view_count),
                verified_at = values(verified_at),
                updated_at = values(updated_at)
            """.trimIndent(),
        ).bind("questionId", entity.questionId)
            .bind("likeCount", entity.likeCount)
            .bind("commentCount", entity.commentCount)
            .bind("viewCount", entity.viewCount)
            .bind("updatedAt", entity.updatedAt)
        statement = entity.verifiedAt?.let { statement.bind("verifiedAt", it) }
            ?: statement.bindNull("verifiedAt", Instant::class.java)
        statement.fetch().rowsUpdated().awaitSingle()
        return entity
    }
    override suspend fun findById(id: Long) = repository.findById(id)
    override suspend fun findAllByIds(ids: Collection<Long>) =
        if (ids.isEmpty()) emptyList() else repository.findAllById(ids).toList()
    override suspend fun incrementView(questionId: Long, delta: Int, now: Instant) = repository.incrementView(questionId, delta, now)
    override suspend fun incrementLike(questionId: Long, delta: Int, now: Instant) = repository.incrementLike(questionId, delta, now)
    override suspend fun incrementComment(questionId: Long, delta: Int, now: Instant) = repository.incrementComment(questionId, delta, now)
    override suspend fun setLikeCount(questionId: Long, count: Int, now: Instant) = repository.setLikeCount(questionId, count, now)
}
