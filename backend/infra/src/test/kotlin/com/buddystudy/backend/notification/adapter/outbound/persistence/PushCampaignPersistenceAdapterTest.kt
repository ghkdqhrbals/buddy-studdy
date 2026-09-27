package com.buddystudy.backend.notification.adapter.outbound.persistence

import com.buddystudy.backend.notification.application.model.*
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

class PushCampaignPersistenceAdapterTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///push-campaigns;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = PushCampaignPersistenceAdapter(database)
    private val now = Instant.parse("2026-09-27T00:00:00Z")
    private val campaign = PushCampaignRecord("ae58f94e-0cb1-4478-84c8-032cc2523784", "This week", "**Highlights**",
        "buddystudy://public", PushCampaignAudience.ALL_REGISTERED, emptyList(), createdAt = now)

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        listOf("push_campaign_recipients", "push_campaigns", "app_notifications", "questions", "voice_study_learning_records", "users").forEach { sql("drop table if exists $it") }
        sql("create table users (id bigint primary key, status varchar(30), allow_public_questions boolean)")
        sql("create table voice_study_learning_records (id bigint primary key, user_id bigint)")
        sql("create table questions (id bigint primary key, user_id bigint, is_public boolean, deleted_at timestamp, status varchar(20), answer varchar(1000), question varchar(1000), record_type varchar(24) default 'QUESTION', voice_record_id bigint)")
        sql("""create table push_campaigns (id varchar(36) primary key, title varchar(160), body text, deep_link varchar(1000),
            audience varchar(32), user_ids_json text, status varchar(16), created_at timestamp, sent_at timestamp,
            archived_recipient_count bigint default 0, archived_accepted_count bigint default 0,
            archived_failed_count bigint default 0, archived_open_count bigint default 0, archived_inbox_open_count bigint default 0)""")
        sql("""create table push_campaign_recipients (campaign_id varchar(36), user_id bigint, event_id varchar(191) unique,
            created_at timestamp, enqueued_at timestamp, primary key(campaign_id,user_id))""")
        sql("""create table app_notifications (id bigint primary key, event_id varchar(191) unique,
            push_sent_at timestamp, push_error varchar(1000), push_opened_at timestamp, inbox_opened_at timestamp,
            deleted_at timestamp, read_at timestamp)""")
        sql("insert into users values (1,'ACTIVE',true),(2,'ACTIVE',true),(3,'ACTIVE',true),(4,'ACTIVE',false),(5,'ANONYMOUS',true),(6,'WITHDRAWN',true),(7,'PENDING_TERMS',true)")
    }

    @Test
    fun `snapshot is active registered recipients with stable unique IDs and bounded drain`(): Unit = runBlocking {
        adapter.createIfAbsent(campaign)
        adapter.createIfAbsent(campaign.copy(title = "duplicate ignored"))
        assertThat(adapter.find(campaign.id)?.title).isEqualTo("This week")
        assertThat(adapter.countAudience(PushCampaignAudience.ALL_REGISTERED, emptyList())).isEqualTo(4)
        adapter.snapshotRecipients(campaign, now)
        assertThat(adapter.find(campaign.id)?.status).isEqualTo(PushCampaignStatus.QUEUED)
        val pending = adapter.pendingRecipientsForUpdate(2)
        assertThat(pending).hasSize(2)
        assertThat(pending.map { it.eventId }).doesNotHaveDuplicates()
        for (row in pending) adapter.markEnqueued(row.campaignId, row.userId, now)
        assertThat(adapter.pendingRecipientsForUpdate(100).map { it.userId }).containsExactly(3L, 4L)
        assertThat(adapter.metrics(listOf(campaign.id))[campaign.id]?.queuedCount).isEqualTo(4)
    }

    @Test
    fun `selected audience snapshots only requested active members`(): Unit = runBlocking {
        val selected = campaign.copy(audience = PushCampaignAudience.SELECTED_USERS, userIds = listOf(2, 3, 5))
        adapter.createIfAbsent(selected)
        assertThat(adapter.find(campaign.id)?.userIds).containsExactly(2L, 3L, 5L)
        assertThat(adapter.countAudience(selected.audience, selected.userIds)).isEqualTo(2)
        adapter.snapshotRecipients(selected, now)
        assertThat(adapter.pendingRecipientsForUpdate(100).map { it.userId }).containsExactly(2L, 3L)
    }

    @Test
    fun `metrics count distinct accepted recipients and never count inbox opens or reads as push clicks`(): Unit = runBlocking {
        adapter.createIfAbsent(campaign)
        adapter.snapshotRecipients(campaign, now)
        for (id in 1..4) sql("insert into app_notifications(id,event_id) values ($id,'push-campaign-${campaign.id}-$id')")
        sql("update app_notifications set push_sent_at=current_timestamp where id in (1,2)")
        sql("update app_notifications set push_error='network failure' where id in (2,3)")
        sql("update app_notifications set read_at=current_timestamp")
        adapter.markOpen(1, NotificationOpenSource.PUSH, now)
        val firstOpen = database.sql("select push_opened_at from app_notifications where id=1")
            .map { row, _ -> row.get("push_opened_at", java.time.LocalDateTime::class.java)!! }.one().awaitSingle()
        adapter.markOpen(1, NotificationOpenSource.PUSH, now.plusSeconds(10))
        adapter.markOpen(2, NotificationOpenSource.INBOX, now)
        adapter.markOpen(3, NotificationOpenSource.PUSH, now) // unaccepted push is not in CTR
        val metrics = adapter.metrics(listOf(campaign.id)).getValue(campaign.id)
        assertThat(metrics.recipientCount).isEqualTo(4)
        assertThat(metrics.acceptedCount).isEqualTo(2)
        assertThat(metrics.failedCount).isEqualTo(1)
        assertThat(metrics.queuedCount).isEqualTo(1)
        assertThat(metrics.uniqueOpenCount).isEqualTo(1)
        assertThat(metrics.inboxOpenCount).isEqualTo(1)
        assertThat(metrics.clickThroughRate).isEqualTo(0.5)
        val repeatedOpen = database.sql("select push_opened_at from app_notifications where id=1")
            .map { row, _ -> row.get("push_opened_at", java.time.LocalDateTime::class.java)!! }.one().awaitSingle()
        assertThat(repeatedOpen).isEqualTo(firstOpen)
        sql("update app_notifications set deleted_at=current_timestamp,read_at=current_timestamp where id=1")
        assertThat(adapter.metrics(listOf(campaign.id)).getValue(campaign.id).uniqueOpenCount).isEqualTo(1)
        sql("update app_notifications set push_sent_at=current_timestamp,push_error=null where id=3")
        val recovered = adapter.metrics(listOf(campaign.id)).getValue(campaign.id)
        assertThat(recovered.acceptedCount).isEqualTo(3)
        assertThat(recovered.failedCount).isZero()
        assertThat(recovered.uniqueOpenCount).isEqualTo(2)
    }

    @Test
    fun `public landing rejects private incomplete deleted and opted out records`(): Unit = runBlocking {
        for (id in 1..6) sql("insert into questions(id,user_id,is_public,deleted_at,status,answer) values ($id,1,true,null,'graded','Answer')")
        sql("update questions set is_public=false where id=2")
        sql("update questions set deleted_at=current_timestamp where id=3")
        sql("update questions set status='grading' where id=4")
        sql("update questions set answer='  ' where id=5")
        sql("update questions set user_id=4 where id=6")
        assertThat(adapter.isPublicQuestion(1)).isTrue()
        for (id in 2L..7L) assertThat(adapter.isPublicQuestion(id)).describedAs("question $id").isFalse()
    }

    @Test
    fun `public voice landing requires matching canonical evidence and completed content`(): Unit = runBlocking {
        sql("insert into voice_study_learning_records(id,user_id) values (100,1),(101,2)")
        for (id in 10..16) sql("insert into questions(id,user_id,is_public,status,answer,question,record_type,voice_record_id) values ($id,1,true,'completed','Answer','Question','VOICE_TUTOR',100)")
        sql("update questions set voice_record_id=null where id=11")
        sql("update questions set voice_record_id=101 where id=12")
        sql("update questions set question='  ' where id=13")
        sql("update questions set answer='  ' where id=14")
        sql("update questions set status='graded' where id=15")
        sql("update questions set is_public=false where id=16")
        assertThat(adapter.isPublicQuestion(10)).isTrue()
        for (id in 11L..16L) assertThat(adapter.isPublicQuestion(id)).describedAs("voice record $id").isFalse()
    }

    @Test
    fun `open events on deleted rows are ignored and pagination stays bounded`(): Unit = runBlocking {
        sql("insert into app_notifications(id,event_id,deleted_at) values (1,'deleted',current_timestamp)")
        adapter.markOpen(1, NotificationOpenSource.PUSH, now)
        val count = database.sql("select count(*) as n from app_notifications where push_opened_at is not null")
            .map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()
        assertThat(count).isZero()
        adapter.createIfAbsent(campaign)
        adapter.createIfAbsent(campaign.copy(id = "ae58f94e-0cb1-4478-84c8-032cc2523785", createdAt = now.plusSeconds(1)))
        assertThat(adapter.count()).isEqualTo(2)
        assertThat(adapter.page(1, 1).single().id).isEqualTo(campaign.id)
    }

    private suspend fun sql(value: String) { database.sql(value).fetch().rowsUpdated().awaitSingle() }
}
