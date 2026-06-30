package com.buddystudy.backend.auth

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.port.outbound.PermissionQueryPort
import com.buddystudy.backend.auth.application.port.outbound.UserPermissionProjection
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccessService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class AccessServiceTest {
    private val users = FakeUserPort()
    private val permissions = FakePermissionQueryPort()
    private val service = AccessService(
        users = users,
        permissions = permissions,
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `access page permissions are calculated from one permission lookup`() {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE", displayName = "Min")
        permissions.rows += UserPermissionProjection(Permissions.PUBLIC_QUESTION_READ, requiresActiveAccount = false)
        permissions.rows += UserPermissionProjection(Permissions.STUDY_READ, requiresActiveAccount = false)
        permissions.rows += UserPermissionProjection(Permissions.RECORD_UPDATE, requiresActiveAccount = true)
        permissions.rows += UserPermissionProjection(Permissions.RECORD_READ, requiresActiveAccount = false)
        permissions.rows += UserPermissionProjection(Permissions.STATS_READ, requiresActiveAccount = false)
        permissions.rows += UserPermissionProjection(Permissions.PROFILE_READ, requiresActiveAccount = false)

        val response = service.access(principal)

        assertThat(response.pageAccess.publicQuestions).isTrue()
        assertThat(response.pageAccess.studyRoom).isTrue()
        assertThat(response.pageAccess.admin).isFalse()
        assertThat(users.findByIdCalls).isEqualTo(1)
        assertThat(permissions.permissionsForUserCalls).isEqualTo(1)
    }

    private class FakeUserPort : UserPort {
        var row: UserEntity? = null
        var findByIdCalls = 0
        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> {
            findByIdCalls += 1
            return Optional.ofNullable(row?.takeIf { it.id == id })
        }
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = row?.let { mutableListOf(it) } ?: mutableListOf()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
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
