package com.buddystudy.backend.appupdate.adapter.outbound.persistence

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenanceOverview
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenancePage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCampaign
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppUpdateMode
import com.buddystudy.backend.appupdate.application.model.AppUpdateUserState
import com.buddystudy.backend.appupdate.application.model.AppControlEventCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceWindow
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationStatus
import com.buddystudy.backend.appupdate.application.port.outbound.AppUpdatePort
import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AppUpdatePersistenceAdapter(
    private val database: DatabaseClient,
) : AppUpdatePort {
    override suspend fun updateDeviceVersion(
        userId: Long,
        deviceId: String,
        version: String,
        build: String,
        seenAt: Instant,
    ) {
        database.sql(
            """
            update devices
            set app_version = :version,
                app_build = :build,
                app_version_seen_at = :seenAt,
                last_seen_at = :seenAt,
                updated_at = :seenAt
            where device_id = :deviceId and user_id = :userId
            """.trimIndent(),
        ).bind("version", version).bind("build", build).bind("seenAt", seenAt)
            .bind("deviceId", deviceId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun recordAppControlEvent(
        userId: Long,
        deviceId: String,
        command: AppControlEventCommand,
        recordedAt: Instant,
    ): Boolean {
        val inserted = database.sql(
            """
            insert ignore into app_control_events
                (event_id, user_id, device_id, event_type, platform, distribution_channel,
                 app_version, app_build, policy_id, policy_revision, campaign_id,
                 evaluated_action, occurred_at, recorded_at)
            values
                (:eventId, :userId, :deviceId, :eventType, :platform, :channel,
                 :version, :build, :policyId, :policyRevision, :campaignId,
                 :evaluatedAction, :occurredAt, :recordedAt)
            """.trimIndent(),
        ).bind("eventId", command.eventId).bind("userId", userId).bind("deviceId", deviceId)
            .bind("eventType", command.event.name).bind("platform", command.platform)
            .bind("channel", command.channel.name).bind("version", command.currentVersion)
            .bind("build", command.currentBuild)
            .bindNullable("policyId", command.policyId, String::class.java)
            .bindNullable("policyRevision", command.policyRevision, Long::class.javaObjectType)
            .bindNullable("campaignId", command.campaignId, Long::class.javaObjectType)
            .bindNullable("evaluatedAction", command.evaluatedAction, String::class.java)
            .bind("occurredAt", command.occurredAt ?: recordedAt).bind("recordedAt", recordedAt)
            .fetch().rowsUpdated().awaitSingle()
        return inserted > 0
    }

    override suspend fun activeCampaign(platform: String): AppUpdateCampaign? =
        database.sql(
            """
            ${campaignSelect()}
            where c.platform = :platform and c.status = 'ACTIVE'
            order by c.activated_at desc, c.id desc
            limit 1
            """.trimIndent(),
        ).bind("platform", platform)
            .map { row, _ -> row.toCampaign() }.one().awaitSingleOrNull()

    override suspend fun userState(campaignId: Long, userId: Long): AppUpdateUserState? =
        database.sql(
            """
            select campaign_id, user_id, device_id, first_version, first_build, current_version, current_build,
                   first_checked_at, last_checked_at, prompted_at, dismissed_at, app_store_opened_at, converted_at
            from app_update_user_states
            where campaign_id = :campaignId and user_id = :userId
            """.trimIndent(),
        ).bind("campaignId", campaignId).bind("userId", userId)
            .map { row, _ -> row.toUserState() }.one().awaitSingleOrNull()

    override suspend fun recordCheck(
        campaignId: Long,
        userId: Long,
        deviceId: String,
        version: String,
        build: String,
        checkedAt: Instant,
    ): AppUpdateUserState {
        database.sql(
            """
            insert into app_update_user_states
                (campaign_id, user_id, device_id, first_version, first_build, current_version, current_build,
                 first_checked_at, last_checked_at)
            values
                (:campaignId, :userId, :deviceId, :version, :build, :version, :build, :checkedAt, :checkedAt)
            on duplicate key update
                device_id = values(device_id),
                current_version = values(current_version),
                current_build = values(current_build),
                last_checked_at = values(last_checked_at)
            """.trimIndent(),
        ).bind("campaignId", campaignId).bind("userId", userId).bind("deviceId", deviceId)
            .bind("version", version).bind("build", build).bind("checkedAt", checkedAt)
            .fetch().rowsUpdated().awaitSingle()
        return requireNotNull(userState(campaignId, userId))
    }

    override suspend fun markConverted(
        campaignId: Long,
        userId: Long,
        version: String,
        build: String,
        convertedAt: Instant,
    ) {
        database.sql(
            """
            update app_update_user_states
            set current_version = :version,
                current_build = :build,
                last_checked_at = :convertedAt,
                converted_at = coalesce(converted_at, :convertedAt)
            where campaign_id = :campaignId and user_id = :userId
            """.trimIndent(),
        ).bind("version", version).bind("build", build).bind("convertedAt", convertedAt)
            .bind("campaignId", campaignId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun recordEvent(
        campaignId: Long,
        userId: Long,
        deviceId: String,
        event: AppUpdateEvent,
        occurredAt: Instant,
    ): Boolean {
        val assignment = when (event) {
            AppUpdateEvent.SHOWN -> "prompted_at = coalesce(prompted_at, :occurredAt)"
            AppUpdateEvent.DISMISSED -> "dismissed_at = coalesce(dismissed_at, :occurredAt)"
            AppUpdateEvent.APP_STORE_OPENED -> "app_store_opened_at = coalesce(app_store_opened_at, :occurredAt)"
        }
        val changed = database.sql(
            """
            update app_update_user_states
            set $assignment, device_id = :deviceId, last_checked_at = greatest(last_checked_at, :occurredAt)
            where campaign_id = :campaignId and user_id = :userId
            """.trimIndent(),
        ).bind("occurredAt", occurredAt).bind("deviceId", deviceId)
            .bind("campaignId", campaignId).bind("userId", userId)
            .fetch().rowsUpdated().awaitSingle()
        return changed > 0
    }

    override suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage {
        val total = database.sql("select count(*) as total_count from app_update_campaigns")
            .map { row, _ -> row.long("total_count") }.one().awaitSingle()
        val campaigns = database.sql(
            """
            ${campaignSummarySelect()}
            order by c.activated_at desc, c.id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bind("limit", limit).bind("offset", offset)
            .map { row, _ -> row.toCampaignSummary() }.all().collectList().awaitSingle()
        return AdminAppUpdateCampaignPage(campaigns, total, limit, offset)
    }

    @Transactional
    override suspend fun createCampaign(
        command: CreateAppUpdateCampaignCommand,
        now: Instant,
    ): AdminAppUpdateCampaignSummary {
        database.sql(
            """
            update app_update_campaigns
            set status = 'ENDED', ended_at = :now, updated_at = :now
            where platform = :platform and status = 'ACTIVE'
            """.trimIndent(),
        ).bind("now", now).bind("platform", command.platform).fetch().rowsUpdated().awaitSingle()
        val id = database.sql(
            """
            insert into app_update_campaigns
                (platform, target_version, target_build, update_mode,
                 title_ko, title_en, title_ja, message_ko, message_en, message_ja,
                 app_store_url, status, created_by, activated_at, created_at, updated_at)
            values
                (:platform, :targetVersion, :targetBuild, :mode,
                 :titleKo, :titleEn, :titleJa, :messageKo, :messageEn, :messageJa,
                 :appStoreUrl, 'ACTIVE', :createdBy, :now, :now, :now)
            """.trimIndent(),
        ).bind("platform", command.platform).bind("targetVersion", command.targetVersion)
            .bind("targetBuild", command.targetBuild).bind("mode", command.mode.name)
            .bind("titleKo", command.titleKo).bind("titleEn", command.titleEn).bind("titleJa", command.titleJa)
            .bind("messageKo", command.messageKo).bind("messageEn", command.messageEn).bind("messageJa", command.messageJa)
            .bind("appStoreUrl", command.appStoreUrl).bind("createdBy", command.createdBy).bind("now", now)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row, _ -> row.long("id") }.one().awaitSingle()
        return requireNotNull(campaignSummary(id))
    }

    @Transactional
    override suspend fun endCampaign(campaignId: Long, now: Instant): AdminAppUpdateCampaignSummary? {
        val changed = database.sql(
            """
            update app_update_campaigns
            set status = 'ENDED', ended_at = coalesce(ended_at, :now), updated_at = :now
            where id = :campaignId
            """.trimIndent(),
        ).bind("now", now).bind("campaignId", campaignId).fetch().rowsUpdated().awaitSingle()
        return if (changed == 0L) null else campaignSummary(campaignId)
    }

    override suspend fun campaignUsers(
        campaignId: Long,
        query: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): AdminAppUpdateUserPage {
        val search = query?.lowercase()?.let { "%$it%" }
        val where = buildString {
            append("where s.campaign_id = :campaignId")
            if (search != null) append(" and (lower(u.email) like :query or lower(u.display_name) like :query or cast(u.id as char) = :exactQuery)")
            when (status) {
                "CONVERTED" -> append(" and s.converted_at is not null")
                "OPENED" -> append(" and s.converted_at is null and s.app_store_opened_at is not null")
                "DISMISSED" -> append(" and s.converted_at is null and s.dismissed_at is not null")
                "PROMPTED" -> append(" and s.converted_at is null and s.dismissed_at is null and s.prompted_at is not null")
                "CHECKED" -> append(" and s.prompted_at is null")
            }
        }
        var count = database.sql(
            "select count(*) as total_count from app_update_user_states s join users u on u.id = s.user_id $where",
        ).bind("campaignId", campaignId)
        var list = database.sql(
            """
            select s.*, u.email, u.display_name
            from app_update_user_states s
            join users u on u.id = s.user_id
            $where
            order by s.last_checked_at desc, s.user_id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bind("campaignId", campaignId)
        if (search != null) {
            count = count.bind("query", search).bind("exactQuery", query.orEmpty())
            list = list.bind("query", search).bind("exactQuery", query.orEmpty())
        }
        val total = count.map { row, _ -> row.long("total_count") }.one().awaitSingle()
        val users = list.bind("limit", limit).bind("offset", offset)
            .map { row, _ -> row.toAdminUserSummary() }.all().collectList().awaitSingle()
        return AdminAppUpdateUserPage(users, total, limit, offset)
    }

    override suspend fun maintenanceOverview(now: Instant): AdminAppControlMaintenanceOverview {
        val windows = database.sql(
            """
            select id, starts_at, ends_at, title_ko, title_en, title_ja,
                   message_ko, message_en, message_ja, status, created_by,
                   terminated_at, created_at, updated_at
            from app_control_maintenance_windows
            where status = 'ACTIVE'
              and terminated_at is null
              and (ends_at is null or ends_at > :now)
            order by starts_at asc, id desc
            limit 21
            """.trimIndent(),
        ).bind("now", now)
            .map { row, _ -> row.toMaintenanceWindow() }.all().collectList().awaitSingle()
        return AdminAppControlMaintenanceOverview(
            current = windows.lastOrNull { !it.startsAt.isAfter(now) },
            upcoming = windows.filter { it.startsAt.isAfter(now) }.take(20),
            checkedAt = now,
        )
    }

    override suspend fun maintenanceHistory(limit: Int, offset: Int): AdminAppControlMaintenancePage {
        val total = database.sql(
            "select count(*) as total_count from app_control_maintenance_windows",
        ).map { row, _ -> row.long("total_count") }.one().awaitSingle()
        val items = database.sql(
            """
            select id, starts_at, ends_at, title_ko, title_en, title_ja,
                   message_ko, message_en, message_ja, status, created_by,
                   terminated_at, created_at, updated_at
            from app_control_maintenance_windows
            order by created_at desc, id desc
            limit :limit offset :offset
            """.trimIndent(),
        ).bind("limit", limit).bind("offset", offset)
            .map { row, _ -> row.toMaintenanceWindow() }.all().collectList().awaitSingle()
        return AdminAppControlMaintenancePage(items, total, limit, offset)
    }

    override suspend fun activeMaintenance(now: Instant): AppControlMaintenanceWindow? =
        database.sql(
            """
            select id, starts_at, ends_at, title_ko, title_en, title_ja,
                   message_ko, message_en, message_ja, status, created_by,
                   terminated_at, created_at, updated_at
            from app_control_maintenance_windows
            where status = 'ACTIVE'
              and terminated_at is null
              and (ends_at is null or ends_at > :now)
            order by starts_at asc, id desc
            limit 1
            """.trimIndent(),
        ).bind("now", now)
            .map { row, _ -> row.toMaintenanceWindow() }.one().awaitSingleOrNull()

    @Transactional
    override suspend fun createMaintenance(
        command: AppControlMaintenanceCommand,
        now: Instant,
    ): AppControlMaintenanceWindow {
        database.sql(
            """
            update app_control_maintenance_windows
            set status = 'ENDED', terminated_at = coalesce(terminated_at, :now), updated_at = :now
            where status = 'ACTIVE'
            """.trimIndent(),
        ).bind("now", now).fetch().rowsUpdated().awaitSingle()
        val id = database.sql(
            """
            insert into app_control_maintenance_windows
                (starts_at, ends_at, title_ko, title_en, title_ja,
                 message_ko, message_en, message_ja, status, created_by,
                 created_at, updated_at)
            values
                (:startsAt, :endsAt, :titleKo, :titleEn, :titleJa,
                 :messageKo, :messageEn, :messageJa, 'ACTIVE', :createdBy,
                 :now, :now)
            """.trimIndent(),
        ).bind("startsAt", command.startsAt)
            .bindNullable("endsAt", command.endsAt, Instant::class.java)
            .bind("titleKo", command.titleKo).bind("titleEn", command.titleEn)
            .bind("titleJa", command.titleJa).bind("messageKo", command.messageKo)
            .bind("messageEn", command.messageEn).bind("messageJa", command.messageJa)
            .bind("createdBy", command.createdBy).bind("now", now)
            .filter { statement -> statement.returnGeneratedValues("id") }
            .map { row, _ -> row.long("id") }.one().awaitSingle()
        return requireNotNull(maintenanceWindow(id))
    }

    @Transactional
    override suspend fun endMaintenance(maintenanceId: Long, now: Instant): AppControlMaintenanceWindow? {
        val changed = database.sql(
            """
            update app_control_maintenance_windows
            set status = 'ENDED', terminated_at = coalesce(terminated_at, :now), updated_at = :now
            where id = :maintenanceId
            """.trimIndent(),
        ).bind("now", now).bind("maintenanceId", maintenanceId)
            .fetch().rowsUpdated().awaitSingle()
        return if (changed == 0L) null else maintenanceWindow(maintenanceId)
    }

    override suspend fun updateRemoteConfigPublication(
        campaignId: Long?,
        status: RemoteConfigPublicationStatus,
        revision: Long?,
        publishedAt: Instant?,
        error: String?,
        now: Instant,
    ) {
        if (campaignId == null) return
        database.sql(
            """
            update app_update_campaigns
            set remote_config_status = :status,
                remote_config_revision = :revision,
                remote_config_published_at = :publishedAt,
                remote_config_error = :error,
                updated_at = :now
            where id = :campaignId
            """.trimIndent(),
        ).bind("status", status.name)
            .bindNullable("revision", revision, Long::class.javaObjectType)
            .bindNullable("publishedAt", publishedAt, Instant::class.java)
            .bindNullable("error", error, String::class.java)
            .bind("now", now).bind("campaignId", campaignId)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun campaignSummary(id: Long): AdminAppUpdateCampaignSummary? =
        database.sql("select * from (${campaignSummarySelect()}) campaign_summary where id = :id")
            .bind("id", id).map { row, _ -> row.toCampaignSummary() }.one().awaitSingleOrNull()

    private suspend fun maintenanceWindow(id: Long): AppControlMaintenanceWindow? =
        database.sql(
            """
            select id, starts_at, ends_at, title_ko, title_en, title_ja,
                   message_ko, message_en, message_ja, status, created_by,
                   terminated_at, created_at, updated_at
            from app_control_maintenance_windows
            where id = :id
            """.trimIndent(),
        ).bind("id", id).map { row, _ -> row.toMaintenanceWindow() }.one().awaitSingleOrNull()

    private fun campaignSelect() =
        """
        select c.id, c.platform, c.target_version, c.target_build, c.update_mode,
               c.title_ko, c.title_en, c.title_ja, c.message_ko, c.message_en, c.message_ja,
               c.app_store_url, c.status, c.created_by, c.activated_at, c.ended_at, c.created_at, c.updated_at
        from app_update_campaigns c
        """.trimIndent()

    private fun campaignSummarySelect() =
        """
        select c.id, c.platform, c.target_version, c.target_build, c.update_mode, c.status,
               c.app_store_url, c.created_by, c.activated_at, c.ended_at,
               c.remote_config_status, c.remote_config_revision,
               c.remote_config_published_at, c.remote_config_error,
               count(s.user_id) as checked_user_count,
               coalesce(sum(case when s.prompted_at is not null then 1 else 0 end), 0) as prompted_user_count,
               coalesce(sum(case when s.app_store_opened_at is not null then 1 else 0 end), 0) as opened_user_count,
               coalesce(sum(case when s.converted_at is not null then 1 else 0 end), 0) as converted_user_count
        from app_update_campaigns c
        left join app_update_user_states s on s.campaign_id = c.id
        group by c.id, c.platform, c.target_version, c.target_build, c.update_mode, c.status,
                 c.app_store_url, c.created_by, c.activated_at, c.ended_at,
                 c.remote_config_status, c.remote_config_revision,
                 c.remote_config_published_at, c.remote_config_error
        """.trimIndent()

    private fun Row.toCampaign() = AppUpdateCampaign(
        id = long("id"), platform = string("platform"), targetVersion = string("target_version"),
        targetBuild = string("target_build"), mode = AppUpdateMode.valueOf(string("update_mode")),
        titleKo = string("title_ko"), titleEn = string("title_en"), titleJa = string("title_ja"),
        messageKo = string("message_ko"), messageEn = string("message_en"), messageJa = string("message_ja"),
        appStoreUrl = string("app_store_url"), status = string("status"), createdBy = string("created_by"),
        activatedAt = instant("activated_at"), endedAt = nullableInstant("ended_at"),
        createdAt = instant("created_at"), updatedAt = instant("updated_at"),
    )

    private fun Row.toUserState() = AppUpdateUserState(
        campaignId = long("campaign_id"), userId = long("user_id"), deviceId = string("device_id"),
        firstVersion = string("first_version"), firstBuild = string("first_build"),
        currentVersion = string("current_version"), currentBuild = string("current_build"),
        firstCheckedAt = instant("first_checked_at"), lastCheckedAt = instant("last_checked_at"),
        promptedAt = nullableInstant("prompted_at"), dismissedAt = nullableInstant("dismissed_at"),
        appStoreOpenedAt = nullableInstant("app_store_opened_at"), convertedAt = nullableInstant("converted_at"),
    )

    private fun Row.toCampaignSummary(): AdminAppUpdateCampaignSummary {
        val prompted = long("prompted_user_count")
        val converted = long("converted_user_count")
        return AdminAppUpdateCampaignSummary(
            id = long("id"), platform = string("platform"), targetVersion = string("target_version"),
            targetBuild = string("target_build"), mode = AppUpdateMode.valueOf(string("update_mode")),
            status = string("status"), appStoreUrl = string("app_store_url"), createdBy = string("created_by"),
            activatedAt = instant("activated_at"), endedAt = nullableInstant("ended_at"),
            checkedUserCount = long("checked_user_count"), promptedUserCount = prompted,
            openedUserCount = long("opened_user_count"), convertedUserCount = converted,
            conversionRate = if (prompted == 0L) 0.0 else converted.toDouble() / prompted.toDouble(),
            remoteConfigStatus = RemoteConfigPublicationStatus.valueOf(
                string("remote_config_status").ifBlank { RemoteConfigPublicationStatus.PENDING.name },
            ),
            remoteConfigRevision = get("remote_config_revision", java.lang.Long::class.java)?.toLong(),
            remoteConfigPublishedAt = nullableInstant("remote_config_published_at"),
            remoteConfigError = get("remote_config_error", String::class.java),
        )
    }

    private fun Row.toMaintenanceWindow() = AppControlMaintenanceWindow(
        id = long("id"),
        startsAt = instant("starts_at"),
        endsAt = nullableInstant("ends_at"),
        titleKo = string("title_ko"),
        titleEn = string("title_en"),
        titleJa = string("title_ja"),
        messageKo = string("message_ko"),
        messageEn = string("message_en"),
        messageJa = string("message_ja"),
        status = string("status"),
        createdBy = string("created_by"),
        terminatedAt = nullableInstant("terminated_at"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private fun Row.toAdminUserSummary(): AdminAppUpdateUserSummary {
        val state = toUserState()
        val status = when {
            state.convertedAt != null -> "CONVERTED"
            state.appStoreOpenedAt != null -> "OPENED"
            state.dismissedAt != null -> "DISMISSED"
            state.promptedAt != null -> "PROMPTED"
            else -> "CHECKED"
        }
        return AdminAppUpdateUserSummary(
            userId = state.userId, email = string("email"), displayName = string("display_name"),
            deviceId = state.deviceId, firstVersion = state.firstVersion, firstBuild = state.firstBuild,
            currentVersion = state.currentVersion, currentBuild = state.currentBuild,
            firstCheckedAt = state.firstCheckedAt, lastCheckedAt = state.lastCheckedAt,
            promptedAt = state.promptedAt, dismissedAt = state.dismissedAt,
            appStoreOpenedAt = state.appStoreOpenedAt, convertedAt = state.convertedAt, status = status,
        )
    }

    private fun Row.string(name: String): String = get(name, String::class.java).orEmpty()
    private fun Row.long(name: String): Long = get(name, java.lang.Long::class.java)?.toLong() ?: 0L
    private fun Row.instant(name: String): Instant = nullableInstant(name) ?: Instant.EPOCH
    private fun Row.nullableInstant(name: String): Instant? =
        get(name, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: T?,
        type: Class<T>,
    ): DatabaseClient.GenericExecuteSpec =
        if (value == null) bindNull(name, type) else bind(name, value)
}
