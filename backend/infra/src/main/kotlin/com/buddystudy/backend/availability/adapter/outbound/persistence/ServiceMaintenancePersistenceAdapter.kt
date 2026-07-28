package com.buddystudy.backend.availability.adapter.outbound.persistence

import com.buddystudy.backend.availability.application.model.CreateServiceMaintenanceCommand
import com.buddystudy.backend.availability.application.model.LocalizedMaintenanceContent
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceHistoryPage
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import com.buddystudy.backend.availability.application.port.outbound.ServiceMaintenancePort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class ServiceMaintenancePersistenceAdapter(
    private val database: DatabaseClient,
) : ServiceMaintenancePort {
    override suspend fun activeAt(now: Instant): ServiceMaintenanceWindow? =
        database.sql(
            """
            ${selectColumns()}
            where terminated_at is null
              and starts_at <= :now
              and (ends_at is null or ends_at > :now)
            order by starts_at desc, id desc
            limit 1
            """.trimIndent(),
        ).bind("now", now.utcDateTime())
            .map { row, _ -> row.toWindow() }
            .one()
            .awaitSingleOrNull()

    override suspend fun upcomingAt(now: Instant, limit: Int): List<ServiceMaintenanceWindow> =
        database.sql(
            """
            ${selectColumns()}
            where terminated_at is null
              and starts_at > :now
              and (ends_at is null or ends_at > :now)
            order by starts_at, id
            limit :limit
            """.trimIndent(),
        ).bind("now", now.utcDateTime())
            .bind("limit", limit)
            .map { row, _ -> row.toWindow() }
            .all()
            .collectList()
            .awaitSingle()

    override suspend fun history(limit: Int, offset: Int): ServiceMaintenanceHistoryPage {
        val total = database.sql("select count(*) as total_count from service_maintenance_windows")
            .map { row, _ -> row.get("total_count", java.lang.Long::class.java)?.toLong() ?: 0L }
            .one()
            .awaitSingle()
        val items = database.sql(
            """
            ${selectColumns()}
            order by created_at desc, id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bind("limit", limit)
            .bind("offset", offset)
            .map { row, _ -> row.toWindow() }
            .all()
            .collectList()
            .awaitSingle()
        return ServiceMaintenanceHistoryPage(items, total, limit, offset)
    }

    override suspend fun hasOverlap(startsAt: Instant, endsAt: Instant?): Boolean {
        var query = database.sql(
            """
            select count(*) as overlap_count
            from service_maintenance_windows
            where terminated_at is null
              and (ends_at is null or ends_at > :startsAt)
              and (:hasEnd = 0 or starts_at < :endsAt)
            """.trimIndent(),
        ).bind("startsAt", startsAt.utcDateTime())
            .bind("hasEnd", if (endsAt == null) 0 else 1)
        query = if (endsAt == null) {
            query.bind("endsAt", Instant.EPOCH.utcDateTime())
        } else {
            query.bind("endsAt", endsAt.utcDateTime())
        }
        return query.map { row, _ ->
            (row.get("overlap_count", java.lang.Long::class.java)?.toLong() ?: 0L) > 0
        }.one().awaitSingle()
    }

    override suspend fun create(
        command: CreateServiceMaintenanceCommand,
        actor: String,
        now: Instant,
    ): ServiceMaintenanceWindow {
        var insert = database.sql(
            """
            insert into service_maintenance_windows (
                title_ko, title_en, title_ja,
                message_ko, message_en, message_ja,
                starts_at, ends_at, created_by, created_at, updated_at
            ) values (
                :titleKo, :titleEn, :titleJa,
                :messageKo, :messageEn, :messageJa,
                :startsAt, :endsAt, :createdBy, :now, :now
            )
            """.trimIndent(),
        ).bind("titleKo", command.content.titleKo)
            .bind("titleEn", command.content.titleEn)
            .bind("titleJa", command.content.titleJa)
            .bind("messageKo", command.content.messageKo)
            .bind("messageEn", command.content.messageEn)
            .bind("messageJa", command.content.messageJa)
            .bind("startsAt", command.startsAt.utcDateTime())
            .bind("createdBy", actor)
            .bind("now", now.utcDateTime())
        insert = command.endsAt?.let { insert.bind("endsAt", it.utcDateTime()) }
            ?: insert.bindNull("endsAt", LocalDateTime::class.java)
        val id = insert.filter { statement -> statement.returnGeneratedValues("id") }
            .map { row, _ -> row.get("id", java.lang.Long::class.java)?.toLong() ?: 0L }
            .one()
            .awaitSingleOrNull()
            ?.takeIf { it > 0 }
            ?: error("Maintenance window insert did not return an id.")
        return findById(id) ?: error("Created maintenance window was not found.")
    }

    override suspend fun terminate(id: Long, actor: String, now: Instant): ServiceMaintenanceWindow? {
        val changed = database.sql(
            """
            update service_maintenance_windows
            set terminated_at = :now, terminated_by = :actor, updated_at = :now
            where id = :id
              and terminated_at is null
              and (ends_at is null or ends_at > :now)
            """.trimIndent(),
        ).bind("now", now.utcDateTime())
            .bind("actor", actor)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return if (changed == 0L) null else findById(id)
    }

    private suspend fun findById(id: Long): ServiceMaintenanceWindow? =
        database.sql("${selectColumns()} where id = :id")
            .bind("id", id)
            .map { row, _ -> row.toWindow() }
            .one()
            .awaitSingleOrNull()

    private fun selectColumns(): String =
        """
        select
            id,
            title_ko, title_en, title_ja,
            message_ko, message_en, message_ja,
            starts_at, ends_at, terminated_at,
            created_by, terminated_by, created_at, updated_at
        from service_maintenance_windows
        """.trimIndent()

    private fun Row.toWindow(): ServiceMaintenanceWindow =
        ServiceMaintenanceWindow(
            id = long("id"),
            content = LocalizedMaintenanceContent(
                titleKo = string("title_ko"),
                titleEn = string("title_en"),
                titleJa = string("title_ja"),
                messageKo = string("message_ko"),
                messageEn = string("message_en"),
                messageJa = string("message_ja"),
            ),
            startsAt = instant("starts_at"),
            endsAt = nullableInstant("ends_at"),
            terminatedAt = nullableInstant("terminated_at"),
            createdBy = string("created_by"),
            terminatedBy = nullableString("terminated_by"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )

    private fun Row.long(name: String): Long =
        get(name, java.lang.Long::class.java)?.toLong() ?: error("$name is missing.")

    private fun Row.string(name: String): String =
        get(name, String::class.java) ?: error("$name is missing.")

    private fun Row.nullableString(name: String): String? = get(name, String::class.java)

    private fun Row.instant(name: String): Instant =
        nullableInstant(name) ?: error("$name is missing.")

    private fun Row.nullableInstant(name: String): Instant? =
        get(name, Instant::class.java)
            ?: get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private fun Instant.utcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
