package com.buddystudy.backend.config

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.RequirePermissionAuthorizationManager
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class RequirePermissionAdvisorTest {
    @Test
    fun `advisor checks annotation before invoking a suspending method`() {
        val evaluator = RecordingPermissionEvaluator()
        val manager = RequirePermissionAuthorizationManager(PermissionChecker(evaluator))
        val target = SecuredHandler()
        val proxy = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvisor(SecurityConfig().requirePermissionAdvisor(manager))
        }.proxy as SecuredHandler
        val authentication = UsernamePasswordAuthenticationToken(
            Principal(
                userId = 7,
                deviceId = "device-7",
                sessionId = 11,
                anonymous = false,
                status = "ACTIVE",
            ),
            null,
            emptyList(),
        )

        val result = mono { proxy.handle() }
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
            .block()

        assertThat(result).isEqualTo("handled")
        assertThat(target.invocations).isEqualTo(1)
        assertThat(evaluator.permissions).containsExactly("sample:execute")
    }

    private open class SecuredHandler {
        var invocations = 0

        @RequirePermission("sample:execute")
        open suspend fun handle(): String {
            invocations += 1
            return "handled"
        }
    }

    private class RecordingPermissionEvaluator : PermissionEvaluator {
        val permissions = mutableListOf<String>()

        override suspend fun evaluate(
            userId: Long,
            deviceId: String,
            permissionCode: String,
            context: PermissionEvaluationContext,
        ): PermissionEvaluationResult {
            permissions += permissionCode
            return PermissionEvaluationResult.granted(permissionCode)
        }
    }
}
