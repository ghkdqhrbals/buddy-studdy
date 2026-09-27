package com.buddystudy.backend.notification

import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.outbox.RedisEventOutboxAppendPort
import com.buddystudy.backend.notification.application.model.*
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.outbound.PushCampaignPersistencePort
import com.buddystudy.backend.notification.application.service.PushCampaignDispatchService
import com.buddystudy.backend.notification.application.service.PushCampaignLandingPolicy
import com.buddystudy.backend.notification.application.service.PushCampaignService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class PushCampaignServiceTest {
    private val store = CampaignStore()
    private val service = PushCampaignService(store)
    private val command = PushCampaignCommand("ae58f94e-0cb1-4478-84c8-032cc2523784", "Weekly highlights", "**This week**",
        "buddystudy://public", PushCampaignAudience.ALL_REGISTERED)

    @Test
    fun `preview and draft never enqueue and create retries preserve one immutable campaign`(): Unit = runBlocking {
        assertThat(service.preview(command).recipientCount).isEqualTo(2)
        assertThat(store.records).isEmpty()
        val first = service.create(command)
        assertThat(first.status).isEqualTo(PushCampaignStatus.DRAFT)
        assertThat(service.create(command).id).isEqualTo(first.id)
        assertThat(store.records).hasSize(1)
        assertThat(store.pending).isEmpty()
        assertThatThrownBy { runBlocking { service.create(command.copy(body = "changed")) } }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `send snapshots active users once and does not grow audience on retry`(): Unit = runBlocking {
        val created = service.create(command)
        val sent = service.send(created.id)
        store.activeUsers += 3L
        val retried = service.send(created.id)
        assertThat(sent.status).isEqualTo(PushCampaignStatus.QUEUED)
        assertThat(sent.recipientCount).isEqualTo(2)
        assertThat(retried.recipientCount).isEqualTo(2)
        assertThat(store.snapshots).isEqualTo(1)
    }

    @Test
    fun `selected audience deduplicates IDs and rejects inactive unknown and unbounded users`(): Unit = runBlocking {
        val selected = command.copy(audience = PushCampaignAudience.SELECTED_USERS, userIds = listOf(2, 1, 2))
        assertThat(service.create(selected).userIds).containsExactly(1L, 2L)
        for (ids in listOf(emptyList(), listOf(9L), listOf(-1L), (1L..501L).toList())) {
            assertThatThrownBy { runBlocking { service.preview(selected.copy(userIds = ids)) } }.isInstanceOf(ApiException::class.java)
        }
        assertThatThrownBy { runBlocking { service.preview(command.copy(userIds = listOf(1))) } }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `broadcast rejects external malformed and private detail links`(): Unit = runBlocking {
        for (link in listOf("https://evil.example", "buddystudy://public@evil.example", "buddystudy://public/questions/1?x=2",
            "buddystudy://public/questions/%31", "buddystudy://public:123/questions/1", "buddystudy://records/1",
            "buddystudy://study/1", "buddystudy://public/questions/0", "buddystudy://public#fragment")) {
            assertThat(PushCampaignLandingPolicy.isSupported(link)).describedAs(link).isFalse()
        }
        for (link in listOf("buddystudy://home/message", "buddystudy://public", "buddystudy://studies", "buddystudy://public/questions/7")) {
            assertThat(PushCampaignLandingPolicy.isSupported(link)).describedAs(link).isTrue()
        }
        assertThatThrownBy { runBlocking { service.preview(command.copy(deepLink = "buddystudy://public/questions/8")) } }
            .isInstanceOf(ApiException::class.java)
        service.preview(command.copy(deepLink = "buddystudy://public/questions/7"))
    }

    @Test
    fun `landing visibility is rechecked when saved draft is sent`(): Unit = runBlocking {
        val draft = service.create(command.copy(deepLink = "buddystudy://public/questions/7"))
        store.publicQuestions.clear()
        assertThatThrownBy { runBlocking { service.send(draft.id) } }.isInstanceOf(ApiException::class.java)
        assertThat(store.snapshots).isZero()
    }

    @Test
    fun `dispatcher appends stable IDs once and preserves markdown and landing`(): Unit = runBlocking {
        val draft = service.create(command)
        service.send(draft.id)
        val outbox = RecordingOutbox()
        val dispatch = PushCampaignDispatchService(store, outbox)
        assertThat(dispatch.dispatchBatch()).isEqualTo(2)
        assertThat(dispatch.dispatchBatch()).isZero()
        assertThat(outbox.commands.map { it.eventId }).doesNotHaveDuplicates()
        assertThat(outbox.commands).allSatisfy {
            assertThat(it.body).isEqualTo(command.body)
            assertThat(it.deepLink).isEqualTo(command.deepLink)
            assertThat(it.shouldPush).isTrue()
            assertThat(it.type).isEqualTo("MARKETING")
        }
    }

    @Test
    fun `outbox failure leaves recipient available for retry`(): Unit = runBlocking {
        val draft = service.create(command.copy(audience = PushCampaignAudience.SELECTED_USERS, userIds = listOf(1)))
        service.send(draft.id)
        val outbox = RecordingOutbox().apply { fail = true }
        val dispatch = PushCampaignDispatchService(store, outbox)
        assertThatThrownBy { runBlocking { dispatch.dispatchBatch() } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(store.pending).hasSize(1)
        outbox.fail = false
        assertThat(dispatch.dispatchBatch()).isEqualTo(1)
    }

    @Test
    fun `metrics use accepted recipients and avoid divide by zero`(): Unit = runBlocking {
        val draft = service.create(command)
        service.send(draft.id)
        store.counts = PushCampaignMetrics(10, 4, 2, 1, 3)
        val response = service.campaign(draft.id)
        assertThat(response.queuedCount).isEqualTo(4)
        assertThat(response.clickThroughRate).isEqualTo(0.25)
        assertThat(response.inboxOpenCount).isEqualTo(3)
        assertThat(PushCampaignMetrics().clickThroughRate).isZero()
    }

    private class RecordingOutbox : RedisEventOutboxAppendPort {
        val commands = mutableListOf<NotificationRequestCommand>()
        var fail = false
        override suspend fun appendNotification(command: NotificationRequestCommand, createdAt: Instant): Long {
            check(!fail) { "Database unavailable" }
            commands += command
            return commands.size.toLong()
        }
    }

    private class CampaignStore : PushCampaignPersistencePort {
        val records = linkedMapOf<String, PushCampaignRecord>()
        val activeUsers = mutableSetOf(1L, 2L)
        val publicQuestions = mutableSetOf(7L)
        val pending = mutableListOf<PendingPushCampaignRecipient>()
        var counts = PushCampaignMetrics()
        var snapshots = 0
        override suspend fun createIfAbsent(record: PushCampaignRecord) { records.putIfAbsent(record.id, record) }
        override suspend fun find(id: String, forUpdate: Boolean) = records[id]
        override suspend fun page(limit: Int, offset: Int) = records.values.drop(offset).take(limit)
        override suspend fun count() = records.size.toLong()
        override suspend fun countAudience(audience: PushCampaignAudience, userIds: List<Long>) =
            activeUsers.count { audience == PushCampaignAudience.ALL_REGISTERED || it in userIds }.toLong()
        override suspend fun isPublicQuestion(questionId: Long) = questionId in publicQuestions
        override suspend fun snapshotRecipients(campaign: PushCampaignRecord, now: Instant) {
            snapshots++
            records[campaign.id] = campaign.copy(status = PushCampaignStatus.QUEUED, sentAt = now)
            activeUsers.filter { campaign.audience == PushCampaignAudience.ALL_REGISTERED || it in campaign.userIds }.forEach {
                pending += PendingPushCampaignRecipient(campaign.id, it, "push-campaign-${campaign.id}-$it", campaign.title, campaign.body, campaign.deepLink)
            }
            counts = PushCampaignMetrics(recipientCount = pending.size.toLong())
        }
        override suspend fun metrics(campaignIds: List<String>) = campaignIds.associateWith { counts }
        override suspend fun pendingRecipientsForUpdate(limit: Int) = pending.take(limit)
        override suspend fun markEnqueued(campaignId: String, userId: Long, now: Instant) { pending.removeIf { it.campaignId == campaignId && it.userId == userId } }
    }
}
