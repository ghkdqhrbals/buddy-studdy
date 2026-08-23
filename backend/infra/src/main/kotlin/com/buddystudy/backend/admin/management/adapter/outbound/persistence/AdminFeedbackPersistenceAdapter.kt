package com.buddystudy.backend.admin.management.adapter.outbound.persistence

import com.buddystudy.backend.admin.management.application.model.AdminFeedbackPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminFeedbackSummary
import com.buddystudy.backend.admin.management.application.port.outbound.AdminFeedbackPort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminFeedbackPersistenceAdapter(
    private val database: DatabaseClient,
) : AdminFeedbackPort {
    override suspend fun feedbacks(
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminFeedbackPageResponse {
        val search = query?.lowercase()?.let { "%$it%" }
        val where = buildString {
            append("where 1 = 1")
            if (search != null) {
                append(
                    """
                     and (
                        lower(f.content) like :query
                        or lower(coalesce(u.email, '')) like :query
                        or lower(coalesce(u.display_name, '')) like :query
                        or cast(f.id as char) = :exactQuery
                        or cast(f.user_id as char) = :exactQuery
                     )
                    """.trimIndent(),
                )
            }
            if (status != null) append(" and f.status = :status")
        }
        val totalCount = bindFilters(
            database.sql(
                """
                select count(*) as total_count
                from feedbacks f
                left join users u on u.id = f.user_id
                $where
                """.trimIndent(),
            ),
            search,
            query,
            status,
        ).map { row, _ -> row.long("total_count") }.one().awaitSingle()
        val rows = bindFilters(
            database.sql(
                """
                ${feedbackSelect()}
                $where
                order by f.created_at desc, f.id desc
                limit :limit offset :offset
                """.trimIndent(),
            ),
            search,
            query,
            status,
        ).bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toFeedbackSummary() }
            .all()
            .collectList()
            .awaitSingle()
        return AdminFeedbackPageResponse(rows, totalCount, limit, offset)
    }

    override suspend fun feedback(feedbackId: Long): AdminFeedbackSummary? =
        database.sql(
            """
            ${feedbackSelect()}
            where f.id = :feedbackId
            """.trimIndent(),
        ).bind("feedbackId", feedbackId)
            .map { row, _ -> row.toFeedbackSummary() }
            .one()
            .awaitSingleOrNull()

    override suspend fun markReviewed(feedbackId: Long, reviewedAt: Instant): AdminFeedbackSummary? {
        database.sql(
            """
            update feedbacks
            set status = case when status = 'NEW' then 'REVIEWED' else status end,
                reviewed_at = coalesce(reviewed_at, :reviewedAt)
            where id = :feedbackId
            """.trimIndent(),
        ).bind("reviewedAt", reviewedAt)
            .bind("feedbackId", feedbackId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return feedback(feedbackId)
    }

    override suspend fun markReplied(feedbackId: Long, repliedAt: Instant): AdminFeedbackSummary? {
        database.sql(
            """
            update feedbacks
            set status = 'REPLIED',
                reviewed_at = coalesce(reviewed_at, :repliedAt),
                replied_at = :repliedAt
            where id = :feedbackId
            """.trimIndent(),
        ).bind("repliedAt", repliedAt)
            .bind("feedbackId", feedbackId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return feedback(feedbackId)
    }

    private fun feedbackSelect(): String =
        """
        select
            f.id,
            f.user_id,
            f.device_id,
            u.email,
            u.display_name,
            f.content,
            f.status,
            f.reviewed_at,
            f.replied_at,
            f.created_at
        from feedbacks f
        left join users u on u.id = f.user_id
        """.trimIndent()

    private fun bindFilters(
        spec: DatabaseClient.GenericExecuteSpec,
        search: String?,
        exactQuery: String?,
        status: String?,
    ): DatabaseClient.GenericExecuteSpec {
        var bound = spec
        if (search != null) {
            bound = bound.bind("query", search).bind("exactQuery", exactQuery.orEmpty())
        }
        if (status != null) bound = bound.bind("status", status)
        return bound
    }

    private fun Row.toFeedbackSummary() = AdminFeedbackSummary(
        id = long("id"),
        userId = get("user_id", java.lang.Long::class.java)?.toLong(),
        deviceId = get("device_id", String::class.java),
        email = get("email", String::class.java),
        displayName = get("display_name", String::class.java),
        content = get("content", String::class.java).orEmpty(),
        status = get("status", String::class.java).orEmpty(),
        reviewedAt = instantOrNull("reviewed_at"),
        repliedAt = instantOrNull("replied_at"),
        createdAt = instantOrNull("created_at") ?: Instant.EPOCH,
    )

    private fun Row.long(name: String): Long =
        get(name, java.lang.Long::class.java)?.toLong() ?: 0L

    private fun Row.instantOrNull(name: String): Instant? =
        get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
}
