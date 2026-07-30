package com.buddystudy.backend.appupdate

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AppControlEventCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceCommand
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceWindow
import com.buddystudy.backend.appupdate.application.model.AppControlRemotePolicy
import com.buddystudy.backend.appupdate.application.model.AppDistributionChannel
import com.buddystudy.backend.appupdate.application.model.AppUpdateCampaign
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppUpdateMode
import com.buddystudy.backend.appupdate.application.model.AppUpdateUserState
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationResult
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationStatus
import com.buddystudy.backend.appupdate.application.port.outbound.AppControlRemoteConfigPort
import com.buddystudy.backend.appupdate.application.port.outbound.AppUpdatePort
import com.buddystudy.backend.appupdate.application.service.AdminAppUpdateService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminAppUpdateServiceTest {
    @Test
    fun `campaign publication targets app store and testflight with one version policy`() = runBlocking {
        val updates = FakeAdminAppUpdatePort()
        val remoteConfig = CapturingRemoteConfigPort()
        val service = AdminAppUpdateService(updates, remoteConfig)

        service.create(
            CreateAppUpdateCampaignCommand(
                platform = "ios",
                targetVersion = "1.2.0",
                targetBuild = "82",
                mode = AppUpdateMode.FORCE,
                titleKo = "업데이트",
                titleEn = "Update",
                titleJa = "更新",
                messageKo = "업데이트가 필요합니다.",
                messageEn = "An update is required.",
                messageJa = "アップデートが必要です。",
                appStoreUrl = "https://apps.apple.com/app/id6774108938",
                createdBy = "test",
            ),
        )

        val policy = requireNotNull(remoteConfig.published)
        assertThat(policy.channels.keys).containsExactlyInAnyOrder(
            AppDistributionChannel.APP_STORE,
            AppDistributionChannel.TESTFLIGHT,
        )
        assertThat(policy.channels.values).allSatisfy {
            assertThat(it.enabled).isTrue()
            assertThat(it.mode).isEqualTo(AppUpdateMode.FORCE)
            assertThat(it.minimumVersion).isEqualTo("1.2.0")
            assertThat(it.minimumBuild).isEqualTo("82")
        }
        assertThat(updates.publicationStatuses).containsExactly(
            RemoteConfigPublicationStatus.PENDING,
            RemoteConfigPublicationStatus.PUBLISHED,
        )
    }

    @Test
    fun `scheduled maintenance is published before its local activation boundary`() = runBlocking {
        val updates = FakeAdminAppUpdatePort()
        val remoteConfig = CapturingRemoteConfigPort()
        val service = AdminAppUpdateService(updates, remoteConfig)
        val startsAt = Instant.parse("2026-08-01T00:00:00Z")

        service.activateMaintenance(
            AppControlMaintenanceCommand(
                startsAt = startsAt,
                endsAt = startsAt.plusSeconds(3600),
                titleKo = "점검",
                titleEn = "Maintenance",
                titleJa = "メンテナンス",
                messageKo = "점검 예정입니다.",
                messageEn = "Maintenance is scheduled.",
                messageJa = "メンテナンスを予定しています。",
                createdBy = "test",
            ),
        )

        val maintenance = requireNotNull(remoteConfig.published).maintenance
        assertThat(maintenance.enabled).isTrue()
        assertThat(maintenance.startsAt).isEqualTo(startsAt)
        assertThat(maintenance.endsAt).isEqualTo(startsAt.plusSeconds(3600))
    }

    private class CapturingRemoteConfigPort : AppControlRemoteConfigPort {
        var published: AppControlRemotePolicy? = null

        override suspend fun publish(policy: AppControlRemotePolicy): RemoteConfigPublicationResult {
            published = policy
            return RemoteConfigPublicationResult(policy.revision, policy.publishedAt)
        }
    }

    private class FakeAdminAppUpdatePort : AppUpdatePort {
        private var activeCampaign: AppUpdateCampaign? = null
        private var activeMaintenance: AppControlMaintenanceWindow? = null
        val publicationStatuses = mutableListOf<RemoteConfigPublicationStatus>()

        override suspend fun createCampaign(
            command: CreateAppUpdateCampaignCommand,
            now: Instant,
        ): AdminAppUpdateCampaignSummary {
            activeCampaign = AppUpdateCampaign(
                id = 41,
                platform = command.platform,
                targetVersion = command.targetVersion,
                targetBuild = command.targetBuild,
                mode = command.mode,
                titleKo = command.titleKo,
                titleEn = command.titleEn,
                titleJa = command.titleJa,
                messageKo = command.messageKo,
                messageEn = command.messageEn,
                messageJa = command.messageJa,
                appStoreUrl = command.appStoreUrl,
                status = "ACTIVE",
                createdBy = command.createdBy,
                activatedAt = now,
                endedAt = null,
                createdAt = now,
                updatedAt = now,
            )
            return summary(requireNotNull(activeCampaign))
        }

        override suspend fun activeCampaign(platform: String): AppUpdateCampaign? = activeCampaign

        override suspend fun campaigns(limit: Int, offset: Int): AdminAppUpdateCampaignPage {
            val campaigns = activeCampaign?.let { listOf(summary(it)) }.orEmpty()
            return AdminAppUpdateCampaignPage(campaigns, campaigns.size.toLong(), limit, offset)
        }

        override suspend fun createMaintenance(
            command: AppControlMaintenanceCommand,
            now: Instant,
        ): AppControlMaintenanceWindow {
            activeMaintenance = AppControlMaintenanceWindow(
                id = 9,
                startsAt = command.startsAt,
                endsAt = command.endsAt,
                titleKo = command.titleKo,
                titleEn = command.titleEn,
                titleJa = command.titleJa,
                messageKo = command.messageKo,
                messageEn = command.messageEn,
                messageJa = command.messageJa,
                status = "ACTIVE",
                createdBy = command.createdBy,
                terminatedAt = null,
                createdAt = now,
                updatedAt = now,
            )
            return requireNotNull(activeMaintenance)
        }

        override suspend fun activeMaintenance(now: Instant): AppControlMaintenanceWindow? =
            activeMaintenance

        override suspend fun updateRemoteConfigPublication(
            campaignId: Long?,
            status: RemoteConfigPublicationStatus,
            revision: Long?,
            publishedAt: Instant?,
            error: String?,
            now: Instant,
        ) {
            publicationStatuses += status
        }

        private fun summary(campaign: AppUpdateCampaign) = AdminAppUpdateCampaignSummary(
            id = campaign.id,
            platform = campaign.platform,
            targetVersion = campaign.targetVersion,
            targetBuild = campaign.targetBuild,
            mode = campaign.mode,
            status = campaign.status,
            appStoreUrl = campaign.appStoreUrl,
            createdBy = campaign.createdBy,
            activatedAt = campaign.activatedAt,
            endedAt = campaign.endedAt,
            checkedUserCount = 0,
            promptedUserCount = 0,
            openedUserCount = 0,
            convertedUserCount = 0,
            conversionRate = 0.0,
            remoteConfigStatus = publicationStatuses.lastOrNull()
                ?: RemoteConfigPublicationStatus.PENDING,
        )

        override suspend fun updateDeviceVersion(
            userId: Long,
            deviceId: String,
            version: String,
            build: String,
            seenAt: Instant,
        ) = unused()

        override suspend fun recordAppControlEvent(
            userId: Long,
            deviceId: String,
            command: AppControlEventCommand,
            recordedAt: Instant,
        ): Boolean = unused()

        override suspend fun userState(campaignId: Long, userId: Long): AppUpdateUserState? = unused()

        override suspend fun recordCheck(
            campaignId: Long,
            userId: Long,
            deviceId: String,
            version: String,
            build: String,
            checkedAt: Instant,
        ): AppUpdateUserState = unused()

        override suspend fun markConverted(
            campaignId: Long,
            userId: Long,
            version: String,
            build: String,
            convertedAt: Instant,
        ) = unused()

        override suspend fun recordEvent(
            campaignId: Long,
            userId: Long,
            deviceId: String,
            event: AppUpdateEvent,
            occurredAt: Instant,
        ): Boolean = unused()

        override suspend fun endCampaign(
            campaignId: Long,
            now: Instant,
        ): AdminAppUpdateCampaignSummary? = unused()

        override suspend fun campaignUsers(
            campaignId: Long,
            query: String?,
            status: String?,
            limit: Int,
            offset: Int,
        ): AdminAppUpdateUserPage = unused()

        override suspend fun endMaintenance(
            maintenanceId: Long,
            now: Instant,
        ): AppControlMaintenanceWindow? = unused()

        private fun unused(): Nothing = error("Not used by this test.")
    }
}
