package com.buddystudy.backend.auth

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccessService
import com.buddystudy.backend.common.application.error.ApiErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class AccessServiceTest {
    private val users = FakeUserPort()
    private val permissions = FakePermissionEvaluator()
    private val service = AccessService(
        users = users,
        permissions = permissions,
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `access page permissions are calculated through permission evaluator`() {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE", displayName = "Min")
        permissions.granted += Permissions.PUBLIC_QUESTION_READ
        permissions.granted += Permissions.STUDY_READ
        permissions.granted += Permissions.RECORD_UPDATE
        permissions.granted += Permissions.RECORD_READ
        permissions.granted += Permissions.PROFILE_READ

        val response = service.access(principal)

        assertThat(response.pageAccess.publicQuestions).isTrue()
        assertThat(response.pageAccess.studyRoom).isTrue()
        assertThat(response.pageAccess.stats).isFalse()
        assertThat(response.pageAccess.admin).isFalse()
        assertThat(users.findByIdCalls).isEqualTo(1)
        assertThat(permissions.calls).contains(Permissions.STATS_READ)
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

    private class FakePermissionEvaluator : PermissionEvaluator {
        val granted = mutableSetOf<String>()
        val calls = mutableListOf<String>()

        override fun evaluate(principal: Principal, permissionCode: String): PermissionEvaluationResult {
            calls += permissionCode
            return if (permissionCode in granted) {
                PermissionEvaluationResult.granted(permissionCode)
            } else {
                PermissionEvaluationResult.denied(
                    permissionCode = permissionCode,
                    failureCode = ApiErrorCode.PERMISSION_DENIED,
                    reason = "Permission denied.",
                )
            }
        }
    }
}
