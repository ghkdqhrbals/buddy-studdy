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
            addAdvisor(SecurityConfig().requirePermissionAdvisor(manager))
        }.proxy as SecuredHandlerPort
        val principal = principal()
        val authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())

        val result = mono { proxy.handle(principal) }
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
            .block()

        assertThat(result).isEqualTo("handled")
        assertThat(target.invocations).isEqualTo(1)
        assertThat(evaluator.permissions).containsExactly("sample:execute")
    }

    @Test
    fun `advisor uses the function principal outside an http security context`() {
        val evaluator = RecordingPermissionEvaluator()
        val manager = RequirePermissionAuthorizationManager(PermissionChecker(evaluator))
        val target = SecuredHandler()
        val proxy = ProxyFactory(target).apply {
            addAdvisor(SecurityConfig().requirePermissionAdvisor(manager))
        }.proxy as SecuredHandlerPort

        val result = mono { proxy.handle(principal()) }.block()

        assertThat(result).isEqualTo("handled")
        assertThat(target.invocations).isEqualTo(1)
        assertThat(evaluator.permissions).containsExactly("sample:execute")
    }

    private interface SecuredHandlerPort {
        suspend fun handle(principal: Principal): String
    }

    private open class SecuredHandler : SecuredHandlerPort {
        var invocations = 0

        @RequirePermission("sample:execute")
        override suspend fun handle(principal: Principal): String {
            invocations += 1
            return "handled"
        }
    }

    private fun principal() = Principal(
        userId = 7,
        deviceId = "device-7",
        sessionId = 11,
        anonymous = false,
        status = "ACTIVE",
    )

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
