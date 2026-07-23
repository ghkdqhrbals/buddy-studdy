package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import org.aopalliance.intercept.MethodInvocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import reactor.core.publisher.Mono
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Method

class RequirePermissionAuthorizationManagerTest {
    private val evaluator = RecordingPermissionEvaluator()
    private val manager = RequirePermissionAuthorizationManager(PermissionChecker(evaluator))

    @Test
    fun `collects class and method permissions with request context`() {
        val authentication = authentication().apply {
            details = ReactiveRequestDetails(appVersion = "1.2.3")
        }

        val result = manager.authorize(Mono.just(authentication), invocation("update")).block()

        assertThat(result?.isGranted).isTrue()
        assertThat(evaluator.calls.map { it.permissionCode })
            .containsExactly("sample:read", "sample:update")
        assertThat(evaluator.calls.map { it.context.appVersion })
            .containsOnly("1.2.3")
    }

    @Test
    fun `propagates the permission failure as an api runtime exception`() {
        evaluator.deniedPermission = "sample:update"

        val error = catchThrowable {
            manager.authorize(Mono.just(authentication()), invocation("update")).block()
        }

        assertThat(error).isInstanceOf(ApiRuntimeException::class.java)
        val apiError = error as ApiRuntimeException
        assertThat(apiError.errorCode).isEqualTo(ApiErrorCode.TERMS_AGREEMENT_REQUIRED)
        assertThat(apiError.requiredPermissions).containsExactly("sample:update")
    }

    private fun authentication() = UsernamePasswordAuthenticationToken(
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

    private fun invocation(methodName: String): MethodInvocation {
        val target = SecuredSample()
        val method = target.javaClass.methods.single { it.name == methodName }
        return TestMethodInvocation(target, method)
    }

    @RequirePermission("sample:read")
    private open class SecuredSample {
        @RequirePermission("sample:update")
        open suspend fun update(): String = "updated"
    }

    private class TestMethodInvocation(
        private val target: Any,
        private val invokedMethod: Method,
    ) : MethodInvocation {
        override fun getMethod(): Method = invokedMethod
        override fun getArguments(): Array<Any?> = emptyArray()
        override fun proceed(): Any? = error("Not used by authorization manager tests.")
        override fun getThis(): Any = target
        override fun getStaticPart(): AccessibleObject = invokedMethod
    }

    private class RecordingPermissionEvaluator : PermissionEvaluator {
        val calls = mutableListOf<Call>()
        var deniedPermission: String? = null

        override suspend fun evaluate(
            userId: Long,
            deviceId: String,
            permissionCode: String,
            context: PermissionEvaluationContext,
        ): PermissionEvaluationResult {
            calls += Call(permissionCode, context)
            if (permissionCode == deniedPermission) {
                return PermissionEvaluationResult.denied(
                    permissionCode = permissionCode,
                    failureCode = ApiErrorCode.TERMS_AGREEMENT_REQUIRED,
                    reason = "Latest terms agreement is required.",
                )
            }
            return PermissionEvaluationResult.granted(permissionCode)
        }
    }

    private data class Call(
        val permissionCode: String,
        val context: PermissionEvaluationContext,
    )
}
