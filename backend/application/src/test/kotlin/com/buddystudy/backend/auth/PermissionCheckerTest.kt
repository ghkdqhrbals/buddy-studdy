package com.buddystudy.backend.auth

import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PermissionCheckerTest {
    private val permissions = FakePermissionQueryPort()
    private val checker = PermissionChecker(
        permissions = permissions,
    )

    @Test
    fun `active account permission check uses principal status without loading user again`() {
        permissions.rows += UserPermissionProjection(Permissions.RECORD_UPDATE, requiresActiveAccount = true)
        val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false, status = "ACTIVE")

        checker.check(principal, listOf(Permissions.RECORD_UPDATE))

        assertThat(permissions.permissionsForUserCalls).isEqualTo(1)
    }

    private class FakePermissionQueryPort : PermissionQueryPort {
        val rows = mutableSetOf<UserPermissionProjection>()
        var permissionsForUserCalls = 0
        override fun permissionsForUser(userId: Long): Set<UserPermissionProjection> {
            permissionsForUserCalls += 1
            return rows
        }
    }
}
