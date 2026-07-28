package com.buddystudy.backend.availability

import com.buddystudy.backend.availability.application.model.CreateServiceMaintenanceCommand
import com.buddystudy.backend.availability.application.model.LocalizedMaintenanceContent
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceHistoryPage
import com.buddystudy.backend.availability.application.model.ServiceMaintenanceWindow
import com.buddystudy.backend.availability.application.port.outbound.ServiceMaintenancePort
import com.buddystudy.backend.availability.application.service.ServiceMaintenanceService
import com.buddystudy.backend.common.application.error.ApiException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

class ServiceMaintenanceServiceTest {
    private val port = FakeServiceMaintenancePort()
    private val service = ServiceMaintenanceService(port)

    @Test
    fun `operational result is cached even when there is no active window`(): Unit = runBlocking {
        service.availability(Locale.ENGLISH)
        service.availability(Locale.ENGLISH)

        assertThat(port.activeAtCalls).isEqualTo(1)
    }

    @Test
    fun `active maintenance is localized for the requested locale`(): Unit = runBlocking {
        port.active = maintenanceWindow()

        val korean = service.availability(Locale.KOREAN)

        assertThat(korean.status).isEqualTo("MAINTENANCE")
        assertThat(korean.maintenanceId).isEqualTo(7)
        assertThat(korean.title).isEqualTo("점검 중")
        assertThat(korean.message).isEqualTo("잠시 기다려 주세요.")
        assertThat(korean.retryAfterSeconds).isBetween(15, 300)
    }

    @Test
    fun `overlapping maintenance window is rejected before insert`() {
        port.overlaps = true
        val command = CreateServiceMaintenanceCommand(
            content = maintenanceContent(),
            startsAt = Instant.now().plusSeconds(600),
            endsAt = Instant.now().plusSeconds(1_200),
        )

        assertThatThrownBy {
            runBlocking { service.create(command, "operator") }
        }.isInstanceOf(ApiException::class.java)
        assertThat(port.created).isNull()
    }

    @Test
    fun `terminating a window invalidates the cached active state`(): Unit = runBlocking {
        port.active = maintenanceWindow()
        service.availability(Locale.ENGLISH)
        port.terminated = port.active?.copy(terminatedAt = Instant.now(), terminatedBy = "operator")

        service.terminate(7, "operator")
        port.active = null
        val status = service.availability(Locale.ENGLISH)

        assertThat(status.status).isEqualTo("OPERATIONAL")
        assertThat(port.activeAtCalls).isEqualTo(2)
    }

    private fun maintenanceWindow(): ServiceMaintenanceWindow {
        val now = Instant.now()
        return ServiceMaintenanceWindow(
            id = 7,
            content = maintenanceContent(),
            startsAt = now.minusSeconds(60),
            endsAt = now.plusSeconds(180),
            terminatedAt = null,
            createdBy = "operator",
            terminatedBy = null,
            createdAt = now.minusSeconds(120),
            updatedAt = now.minusSeconds(120),
        )
    }

    private fun maintenanceContent() = LocalizedMaintenanceContent(
        titleKo = "점검 중",
        titleEn = "Maintenance",
        titleJa = "メンテナンス",
        messageKo = "잠시 기다려 주세요.",
        messageEn = "Please try again shortly.",
        messageJa = "しばらくお待ちください。",
    )
}

private class FakeServiceMaintenancePort : ServiceMaintenancePort {
    var active: ServiceMaintenanceWindow? = null
    var terminated: ServiceMaintenanceWindow? = null
    var overlaps = false
    var activeAtCalls = 0
    var created: CreateServiceMaintenanceCommand? = null

    override suspend fun activeAt(now: Instant): ServiceMaintenanceWindow? {
        activeAtCalls += 1
        return active
    }

    override suspend fun upcomingAt(now: Instant, limit: Int): List<ServiceMaintenanceWindow> = emptyList()

    override suspend fun history(limit: Int, offset: Int) =
        ServiceMaintenanceHistoryPage(emptyList(), 0, limit, offset)

    override suspend fun hasOverlap(startsAt: Instant, endsAt: Instant?): Boolean = overlaps

    override suspend fun create(
        command: CreateServiceMaintenanceCommand,
        actor: String,
        now: Instant,
    ): ServiceMaintenanceWindow {
        created = command
        error("Create should not be called in this test.")
    }

    override suspend fun terminate(id: Long, actor: String, now: Instant): ServiceMaintenanceWindow? =
        terminated
}
