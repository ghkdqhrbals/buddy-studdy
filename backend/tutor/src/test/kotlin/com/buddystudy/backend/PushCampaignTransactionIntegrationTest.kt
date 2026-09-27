package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.notification.application.model.*
import com.buddystudy.backend.notification.application.port.inbound.*
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@SpringBootTest
@Import(PushCampaignTransactionIntegrationTest.RollbackConfig::class)
@TestPropertySource(properties = [
    "buddystudy.scheduler.enabled=false",
    "buddystudy.streams.enabled=false",
    "buddystudy.analytics.datasource.database-name=",
    "buddystudy.crypto.master-key=test-master-key",
    "buddystudy.auth.jwt-secret=test-jwt-secret",
])
class PushCampaignTransactionIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var database: DatabaseClient
    @Autowired lateinit var campaigns: ManagePushCampaignsUseCase
    @Autowired lateinit var dispatch: DispatchPushCampaignsUseCase
    @Autowired lateinit var rollback: RollbackDispatch
    @Autowired lateinit var notifications: ProcessNotificationEventUseCase
    @Autowired lateinit var notificationStore: NotificationPersistencePort
    @Autowired lateinit var opens: TrackNotificationOpenUseCase
    @Autowired lateinit var mutations: MutateNotificationsUseCase
    @Autowired lateinit var accountDeletion: AccountDeletionPort

    @Test
    fun `mysql migration concurrent send rollback and replay preserve recipient and outbox identity`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val userIds = (1..2).map { index ->
            users.save(UserEntity(provider = UserProvider.EMAIL, providerId = "$suffix-$index@test.invalid",
                email = "$suffix-$index@test.invalid", status = UserStatus.ACTIVE,
                displayName = "Push-${suffix.take(8)}-$index")).id
        }
        val command = PushCampaignCommand(suffix, "Weekly highlights", "**Read this week**",
            "buddystudy://public/questions", PushCampaignAudience.SELECTED_USERS, userIds + userIds.first())
        val drafts = coroutineScope {
            listOf(async(Dispatchers.IO) { campaigns.create(command) }, async(Dispatchers.IO) { campaigns.create(command) }).awaitAll()
        }
        assertThat(drafts.map { it.id }).containsOnly(suffix)
        assertThat(count("push_campaigns", "id = '$suffix'")).isEqualTo(1)
        val draft = drafts.first()
        val sent = coroutineScope {
            listOf(async(Dispatchers.IO) { campaigns.send(draft.id) }, async(Dispatchers.IO) { campaigns.send(draft.id) }).awaitAll()
        }
        assertThat(sent).allSatisfy { assertThat(it.recipientCount).isEqualTo(2) }
        assertThat(count("push_campaign_recipients", "campaign_id = '$suffix'")).isEqualTo(2)
        assertThat(count("redis_event_outbox", "event_id like 'push-campaign-$suffix-%'")).isZero()

        assertThat(runCatching { rollback.dispatchAndFail() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(count("push_campaign_recipients", "campaign_id = '$suffix' and enqueued_at is not null")).isZero()
        assertThat(count("redis_event_outbox", "event_id like 'push-campaign-$suffix-%'")).isZero()

        assertThat(dispatch.dispatchBatch()).isEqualTo(2)
        assertThat(dispatch.dispatchBatch()).isZero()
        assertThat(count("redis_event_outbox", "event_id like 'push-campaign-$suffix-%'")).isEqualTo(2)
        campaigns.send(draft.id)
        assertThat(count("push_campaign_recipients", "campaign_id = '$suffix'")).isEqualTo(2)

        // Materialize the actual inbox rows, without contacting Redis or APNs.
        val notificationIds = userIds.map { userId -> notifications.process(NotificationRequestCommand(
            eventId = "push-campaign-$suffix-$userId", userId = userId, title = draft.title, body = draft.body,
            deepLink = draft.deepLink, type = "MARKETING", shouldPush = true,
        )) }
        for (id in notificationIds) notificationStore.markPushSent(id, Instant.now())
        val viewer = Principal(userIds.first(), "test-device", 1, false)
        opens.open(viewer, notificationIds.first(), NotificationOpenSource.PUSH)
        opens.open(viewer.copy(deviceId = "second-device"), notificationIds.first(), NotificationOpenSource.PUSH)
        opens.open(viewer, notificationIds.first(), NotificationOpenSource.INBOX)
        assertThat(runCatching { opens.open(viewer, notificationIds.last(), NotificationOpenSource.PUSH) }.exceptionOrNull()).isNotNull()
        mutations.markAllRead(viewer)
        mutations.delete(viewer, notificationIds.first())
        val result = campaigns.campaign(draft.id)
        assertThat(result.acceptedCount).isEqualTo(2)
        assertThat(result.uniqueOpenCount).isEqualTo(1)
        assertThat(result.inboxOpenCount).isEqualTo(1)
        assertThat(result.clickThroughRate).isEqualTo(0.5)

        val pendingCampaign = campaigns.create(command.copy(idempotencyKey = UUID.randomUUID().toString(), userIds = listOf(userIds.first())))
        campaigns.send(pendingCampaign.id)
        val unsentDraft = campaigns.create(command.copy(idempotencyKey = UUID.randomUUID().toString(), userIds = listOf(userIds.first())))
        val withdrawnAt = Instant.now()
        val withdrawal = accountDeletion.beginWithdrawal(userIds.first(), withdrawnAt)
        accountDeletion.deleteAccountData(userIds.first(), withdrawal.deviceIds, withdrawnAt)
        accountDeletion.deleteAccountData(userIds.first(), withdrawal.deviceIds, withdrawnAt)
        val archived = campaigns.campaign(draft.id)
        assertThat(archived.recipientCount).isEqualTo(2)
        assertThat(archived.acceptedCount).isEqualTo(2)
        assertThat(archived.uniqueOpenCount).isEqualTo(1)
        assertThat(archived.clickThroughRate).isEqualTo(0.5)
        assertThat(archived.userIds).containsExactly(userIds.last())
        assertThat(campaigns.campaign(unsentDraft.id).userIds).isEmpty()
        val withdrawnPending = campaigns.campaign(pendingCampaign.id)
        assertThat(withdrawnPending.recipientCount).isEqualTo(1)
        assertThat(withdrawnPending.failedCount).isEqualTo(1)
        assertThat(withdrawnPending.queuedCount).isZero()
        assertThat(count("push_campaign_recipients", "user_id = ${userIds.first()}")).isZero()
    }

    private suspend fun count(table: String, condition: String): Long =
        database.sql("select count(*) as n from $table where $condition")
            .map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()

    open class RollbackDispatch(private val dispatch: DispatchPushCampaignsUseCase) {
        @Transactional
        open suspend fun dispatchAndFail() {
            dispatch.dispatchBatch()
            throw IllegalStateException("forced rollback after durable dispatch")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RollbackConfig {
        @Bean
        fun rollbackDispatch(dispatch: DispatchPushCampaignsUseCase) = RollbackDispatch(dispatch)
    }
}
