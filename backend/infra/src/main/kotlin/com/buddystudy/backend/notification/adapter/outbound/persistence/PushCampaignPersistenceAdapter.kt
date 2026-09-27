package com.buddystudy.backend.notification.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.notification.application.model.*
import com.buddystudy.backend.notification.application.port.outbound.NotificationOpenPersistencePort
import com.buddystudy.backend.notification.application.port.outbound.PushCampaignPersistencePort
import com.buddystudy.backend.notification.application.port.outbound.ArchivePushCampaignOutcomesPort
import com.buddystudy.backend.study.adapter.outbound.persistence.QuestionRepository
import com.fasterxml.jackson.module.kotlin.readValue
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
class PushCampaignPersistenceAdapter(private val database: DatabaseClient) : PushCampaignPersistencePort, NotificationOpenPersistencePort, ArchivePushCampaignOutcomesPort {
    override suspend fun createIfAbsent(record: PushCampaignRecord) {
        database.sql("""
            insert ignore into push_campaigns (id, title, body, deep_link, audience, user_ids_json, status, created_at)
            values (:id, :title, :body, :deepLink, :audience, :userIds, 'DRAFT', :now)
        """).bind("id", record.id).bind("title", record.title).bind("body", record.body)
            .bind("deepLink", record.deepLink).bind("audience", record.audience.name)
            .bind("userIds", JsonMapperProvider.mapper.writeValueAsString(record.userIds))
            .bind("now", record.createdAt).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun find(id: String, forUpdate: Boolean): PushCampaignRecord? =
        database.sql("select * from push_campaigns where id = :id" + if (forUpdate) " for update" else "")
            .bind("id", id).map { row, _ -> row.campaign() }.one().awaitSingleOrNull()

    override suspend fun page(limit: Int, offset: Int): List<PushCampaignRecord> =
        database.sql("select * from push_campaigns order by created_at desc, id desc limit :limit offset :offset")
            .bind("limit", limit).bind("offset", offset).map { row, _ -> row.campaign() }.all().collectList().awaitSingle()

    override suspend fun count(): Long = database.sql("select count(*) as n from push_campaigns")
        .map { row, _ -> row.long("n") }.one().awaitSingle()

    override suspend fun countAudience(audience: PushCampaignAudience, userIds: List<Long>): Long {
        if (audience == PushCampaignAudience.SELECTED_USERS && userIds.isEmpty()) return 0
        var query = database.sql("select count(*) as n from users where status = 'ACTIVE'" +
            if (audience == PushCampaignAudience.SELECTED_USERS) " and id in (:userIds)" else "")
        if (audience == PushCampaignAudience.SELECTED_USERS) query = query.bind("userIds", userIds)
        return query.map { row, _ -> row.long("n") }.one().awaitSingle()
    }

    override suspend fun isPublicQuestion(questionId: Long): Boolean =
        database.sql("""
            select count(*) as n from questions q join users u on u.id = q.user_id
            where q.id = :id and q.is_public = true and q.deleted_at is null
              and (${QuestionRepository.PUBLIC_ANSWER_CONDITION})
              and u.allow_public_questions = true and u.status = 'ACTIVE'
        """)
            .bind("id", questionId).map { row, _ -> row.long("n") > 0 }.one().awaitSingle()

    override suspend fun snapshotRecipients(campaign: PushCampaignRecord, now: Instant) {
        var query = database.sql("""
            insert into push_campaign_recipients (campaign_id, user_id, event_id, created_at)
            select :campaignId, u.id, concat('push-campaign-', :campaignId, '-', u.id), :now
            from users u where u.status = 'ACTIVE'
        """ + if (campaign.audience == PushCampaignAudience.SELECTED_USERS) " and u.id in (:userIds)" else "")
            .bind("campaignId", campaign.id).bind("now", now)
        if (campaign.audience == PushCampaignAudience.SELECTED_USERS) query = query.bind("userIds", campaign.userIds)
        query.fetch().rowsUpdated().awaitSingle()
        database.sql("update push_campaigns set status = 'QUEUED', sent_at = :now where id = :id and status = 'DRAFT'")
            .bind("now", now).bind("id", campaign.id).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun metrics(campaignIds: List<String>): Map<String, PushCampaignMetrics> {
        if (campaignIds.isEmpty()) return emptyMap()
        return database.sql("""
            select c.id as campaign_id, c.archived_recipient_count + count(r.event_id) as recipient_count,
                c.archived_accepted_count + sum(case when n.push_sent_at is not null then 1 else 0 end) as accepted_count,
                c.archived_failed_count + sum(case when n.push_sent_at is null and n.push_error is not null then 1 else 0 end) as failed_count,
                c.archived_open_count + sum(case when n.push_sent_at is not null and n.push_opened_at is not null then 1 else 0 end) as open_count,
                c.archived_inbox_open_count + sum(case when n.inbox_opened_at is not null then 1 else 0 end) as inbox_open_count
            from push_campaigns c
            left join push_campaign_recipients r on r.campaign_id = c.id
            left join app_notifications n on n.event_id = r.event_id
            where c.id in (:ids)
            group by c.id, c.archived_recipient_count, c.archived_accepted_count, c.archived_failed_count,
                c.archived_open_count, c.archived_inbox_open_count
        """).bind("ids", campaignIds).map { row, _ ->
            row.string("campaign_id") to PushCampaignMetrics(row.long("recipient_count"), row.long("accepted_count"),
                row.long("failed_count"), row.long("open_count"), row.long("inbox_open_count"))
        }.all().collectList().awaitSingle().toMap()
    }

    override suspend fun pendingRecipientsForUpdate(limit: Int): List<PendingPushCampaignRecipient> =
        database.sql("""
            select r.campaign_id, r.user_id, r.event_id, c.title, c.body, c.deep_link
            from push_campaign_recipients r join push_campaigns c on c.id = r.campaign_id
            where r.enqueued_at is null and c.status = 'QUEUED'
            order by r.created_at, r.campaign_id, r.user_id
            limit :limit for update skip locked
        """).bind("limit", limit).map { row, _ ->
            PendingPushCampaignRecipient(row.string("campaign_id"), row.long("user_id"), row.string("event_id"),
                row.string("title"), row.string("body"), row.string("deep_link"))
        }.all().collectList().awaitSingle()

    override suspend fun markEnqueued(campaignId: String, userId: Long, now: Instant) {
        database.sql("""
            update push_campaign_recipients set enqueued_at = :now
            where campaign_id = :id and user_id = :userId and enqueued_at is null
        """).bind("now", now).bind("id", campaignId).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
    }

    override suspend fun markOpen(notificationId: Long, source: NotificationOpenSource, now: Instant) {
        val column = when (source) {
            NotificationOpenSource.PUSH -> "push_opened_at"
            NotificationOpenSource.INBOX -> "inbox_opened_at"
        }
        database.sql("update app_notifications set $column = :now where id = :id and $column is null and deleted_at is null")
            .bind("now", now).bind("id", notificationId).fetch().rowsUpdated().awaitSingle()
    }

    @Transactional
    override suspend fun archiveAndForgetUser(userId: Long) {
        // A locking current read sees the latest delivery/open outcome even when account
        // cleanup already established a REPEATABLE READ snapshot. Inbox mutations cannot
        // race this capture. Archive + receipt removal + inbox removal share one transaction.
        val outcomes = database.sql("""
            select r.campaign_id, n.push_sent_at, n.push_opened_at, n.inbox_opened_at
            from push_campaign_recipients r
            left join app_notifications n on n.event_id = r.event_id
            where r.user_id = :userId
            order by r.campaign_id
            for update
        """).bind("userId", userId).map { row, _ ->
            val accepted = row.get("push_sent_at") != null
            row.string("campaign_id") to PushCampaignMetrics(
                recipientCount = 1,
                acceptedCount = if (accepted) 1 else 0,
                // Withdrawal makes an unaccepted recipient permanently undeliverable.
                failedCount = if (accepted) 0 else 1,
                uniqueOpenCount = if (accepted && row.get("push_opened_at") != null) 1 else 0,
                inboxOpenCount = if (row.get("inbox_opened_at") != null) 1 else 0,
            )
        }.all().collectList().awaitSingle()
        for ((campaignId, counts) in outcomes) {
            database.sql("""
                update push_campaigns set
                    archived_recipient_count = archived_recipient_count + :recipients,
                    archived_accepted_count = archived_accepted_count + :accepted,
                    archived_failed_count = archived_failed_count + :failed,
                    archived_open_count = archived_open_count + :opens,
                    archived_inbox_open_count = archived_inbox_open_count + :inbox
                where id = :id
            """).bind("id", campaignId).bind("recipients", counts.recipientCount)
                .bind("accepted", counts.acceptedCount).bind("failed", counts.failedCount)
                .bind("opens", counts.uniqueOpenCount).bind("inbox", counts.inboxOpenCount)
                .fetch().rowsUpdated().awaitSingle()
        }
        database.sql("delete from push_campaign_recipients where user_id = :userId")
            .bind("userId", userId).fetch().rowsUpdated().awaitSingle()
        // Remove the member identifier from saved selected-audience definitions too.
        val selected = database.sql("""
            select id, user_ids_json from push_campaigns
            where audience = 'SELECTED_USERS' and json_contains(user_ids_json, :userJson, '$')
            for update
        """).bind("userJson", userId.toString()).map { row, _ ->
            row.string("id") to JsonMapperProvider.mapper.readValue<List<Long>>(row.string("user_ids_json"))
        }.all().collectList().awaitSingle()
        for ((id, ids) in selected) {
            database.sql("update push_campaigns set user_ids_json = :ids where id = :id")
                .bind("id", id).bind("ids", JsonMapperProvider.mapper.writeValueAsString(ids.filterNot { it == userId }))
                .fetch().rowsUpdated().awaitSingle()
        }
    }

    private fun Row.campaign() = PushCampaignRecord(
        id = string("id"), title = string("title"), body = string("body"), deepLink = string("deep_link"),
        audience = PushCampaignAudience.valueOf(string("audience")),
        userIds = JsonMapperProvider.mapper.readValue(string("user_ids_json")),
        status = PushCampaignStatus.valueOf(string("status")), createdAt = instant("created_at")!!, sentAt = instant("sent_at"),
    )

    private fun Row.string(name: String) = get(name, String::class.java).orEmpty()
    private fun Row.long(name: String) = (get(name) as? Number)?.toLong() ?: 0L
    private fun Row.instant(name: String): Instant? = when (val value = get(name)) {
        null -> null
        is Instant -> value
        is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
        else -> error("Unexpected timestamp type for $name: ${value.javaClass.name}")
    }
}
