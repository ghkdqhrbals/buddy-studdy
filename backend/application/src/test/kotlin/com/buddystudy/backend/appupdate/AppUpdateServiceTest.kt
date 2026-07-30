package com.buddystudy.backend.appupdate

import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignPage
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateCampaignSummary
import com.buddystudy.backend.appupdate.application.model.AdminAppUpdateUserPage
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenanceOverview
import com.buddystudy.backend.appupdate.application.model.AdminAppControlMaintenancePage
import com.buddystudy.backend.appupdate.application.model.AppUpdateCampaign
import com.buddystudy.backend.appupdate.application.model.AppUpdateCheckCommand
import com.buddystudy.backend.appupdate.application.model.AppUpdateEvent
import com.buddystudy.backend.appupdate.application.model.AppUpdateMode
import com.buddystudy.backend.appupdate.application.model.AppUpdateUserState
import com.buddystudy.backend.appupdate.application.model.AppControlEventCommand
import com.buddystudy.backend.appupdate.application.model.AppControlEventType
import com.buddystudy.backend.appupdate.application.model.AppControlMaintenanceCommand
import com.buddystudy.backend.appupdate.application.model.AppDistributionChannel
import com.buddystudy.backend.appupdate.application.model.RemoteConfigPublicationStatus
import com.buddystudy.backend.appupdate.application.model.CreateAppUpdateCampaignCommand
import com.buddystudy.backend.appupdate.application.port.outbound.AppUpdatePort
import com.buddystudy.backend.appupdate.application.service.AppUpdateService
import com.buddystudy.backend.appupdate.application.service.VersionNumber
import com.buddystudy.backend.auth.Principal
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AppUpdateServiceTest {
    @Test
    fun `version comparison uses semantic version before build`() {
        assertThat(VersionNumber("1.10.0", "1")).isGreaterThan(VersionNumber("1.9.9", "999"))
        assertThat(VersionNumber("1.1.0", "71")).isGreaterThan(VersionNumber("1.1.0", "70"))
        assertThat(VersionNumber("2.0", "1")).isEqualByComparingTo(VersionNumber("2.0.0", "1"))
    }

    @Test
    fun `latest user receives no prompt and is marked converted`() = runBlocking {
        val port = FakeAppUpdatePort()
        port.state = port.defaultState()
        val result = AppUpdateService(port).check(
            Principal(7, "device-7", 1, false),
            AppUpdateCheckCommand("ios", "1.1.0", "71", "ko"),
        )

        assertThat(result.updateAvailable).isFalse()
        assertThat(port.converted).isTrue()
        assertThat(port.lastDeviceVersion).isEqualTo("1.1.0" to "71")
    }

    @Test
    fun `optional campaign remains hidden after user dismissed it`() = runBlocking {
        val port = FakeAppUpdatePort()
        port.state = port.defaultState(dismissedAt = Instant.now())
        val result = AppUpdateService(port).check(
            Principal(7, "device-7", 1, false),
            AppUpdateCheckCommand("ios", "1.0.0", "60", "en"),
        )

        assertThat(result.updateAvailable).isTrue()
        assertThat(result.shouldPresent).isFalse()
        assertThat(result.title).isEqualTo("Update")
    }

    @Test
    fun `forced campaign remains visible after a prior dismissal timestamp`() = runBlocking {
        val port = FakeAppUpdatePort(mode = AppUpdateMode.FORCE)
        port.state = port.defaultState(dismissedAt = Instant.now())
        val result = AppUpdateService(port).check(
            Principal(7, "device-7", 1, false),
            AppUpdateCheckCommand("ios", "1.0.0", "60", "ja"),
        )

        assertThat(result.shouldPresent).isTrue()
        assertThat(result.mode).isEqualTo(AppUpdateMode.FORCE)
        assertThat(result.title).isEqualTo("更新")
    }

    @Test
    fun `app control observation updates the exact authenticated device and is idempotent`() = runBlocking {
        val port = FakeAppUpdatePort()
        val service = AppUpdateService(port)
        val command = AppControlEventCommand(
            eventId = "device-7-launch-1",
            event = AppControlEventType.VERSION_OBSERVED,
            platform = "ios",
            channel = AppDistributionChannel.TESTFLIGHT,
            currentVersion = "1.2.0",
            currentBuild = "81",
            policyId = "ios-81",
            policyRevision = 81,
            campaignId = null,
            evaluatedAction = "NORMAL",
            occurredAt = Instant.EPOCH,
        )

        service.recordAppControlEvent(Principal(7, "device-7", 1, false), command)
        service.recordAppControlEvent(Principal(7, "device-7", 1, false), command)

        assertThat(port.lastDeviceIdentity).isEqualTo(7L to "device-7")
        assertThat(port.lastDeviceVersion).isEqualTo("1.2.0" to "81")
        assertThat(port.appControlEvents).containsExactly("device-7-launch-1")
    }

    private class FakeAppUpdatePort(mode: AppUpdateMode = AppUpdateMode.OPTIONAL) : AppUpdatePort {
        var state: AppUpdateUserState? = null
        var converted = false
        var lastDeviceIdentity: Pair<Long, String>? = null
        var lastDeviceVersion: Pair<String, String>? = null
        val appControlEvents = mutableListOf<String>()
        private val campaign = AppUpdateCampaign(
            id = 3, platform = "ios", targetVersion = "1.1.0", targetBuild = "71",
            mode = mode, titleKo = "업데이트", titleEn = "Update", titleJa = "更新",
            messageKo = "메시지", messageEn = "Message", messageJa = "メッセージ",
            appStoreUrl = "https://apps.apple.com/app/id6774108938", status = "ACTIVE",
            createdBy = "test", activatedAt = Instant.EPOCH, endedAt = null,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )

        fun defaultState(dismissedAt: Instant? = null) = AppUpdateUserState(
            campaignId = campaign.id, userId = 7, deviceId = "device-7",
            firstVersion = "1.0.0", firstBuild = "60", currentVersion = "1.0.0", currentBuild = "60",
            firstCheckedAt = Instant.EPOCH, lastCheckedAt = Instant.EPOCH,
            promptedAt = Instant.EPOCH, dismissedAt = dismissedAt, appStoreOpenedAt = null, convertedAt = null,
        )

        override suspend fun updateDeviceVersion(userId: Long, deviceId: String, version: String, build: String, seenAt: Instant) {
            lastDeviceIdentity = userId to deviceId
            lastDeviceVersion = version to build
        }
        override suspend fun recordAppControlEvent(
            userId: Long,
            deviceId: String,
            command: AppControlEventCommand,
            recordedAt: Instant,
        ): Boolean = if (command.eventId in appControlEvents) {
            false
        } else {
            appControlEvents += command.eventId
            true
        }
        override suspend fun activeCampaign(platform: String) = campaign
        override suspend fun userState(campaignId: Long, userId: Long) = state
        override suspend fun recordCheck(campaignId: Long, userId: Long, deviceId: String, version: String, build: String, checkedAt: Instant): AppUpdateUserState =
            state ?: defaultState().copy(currentVersion = version, currentBuild = build)
        override suspend fun markConverted(campaignId: Long, userId: Long, version: String, build: String, convertedAt: Instant) {
            converted = true
        }
        override suspend fun recordEvent(campaignId: Long, userId: Long, deviceId: String, event: AppUpdateEvent, occurredAt: Instant) = true
        override suspend fun campaigns(limit: Int, offset: Int) = AdminAppUpdateCampaignPage(emptyList(), 0, limit, offset)
        override suspend fun createCampaign(command: CreateAppUpdateCampaignCommand, now: Instant): AdminAppUpdateCampaignSummary =
            error("not used")
        override suspend fun endCampaign(campaignId: Long, now: Instant): AdminAppUpdateCampaignSummary? = null
        override suspend fun campaignUsers(campaignId: Long, query: String?, status: String?, limit: Int, offset: Int) =
            AdminAppUpdateUserPage(emptyList(), 0, limit, offset)
        override suspend fun maintenanceOverview(now: Instant) =
            AdminAppControlMaintenanceOverview(null, emptyList(), now)
        override suspend fun maintenanceHistory(limit: Int, offset: Int) =
            AdminAppControlMaintenancePage(emptyList(), 0, limit, offset)
        override suspend fun activeMaintenance(now: Instant) = null
        override suspend fun createMaintenance(command: AppControlMaintenanceCommand, now: Instant) =
            error("not used")
        override suspend fun endMaintenance(maintenanceId: Long, now: Instant) = null
        override suspend fun updateRemoteConfigPublication(
            campaignId: Long?,
            status: RemoteConfigPublicationStatus,
            revision: Long?,
            publishedAt: Instant?,
            error: String?,
            now: Instant,
        ) = Unit
    }
}
