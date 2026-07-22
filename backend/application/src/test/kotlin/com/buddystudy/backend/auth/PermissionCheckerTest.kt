package com.buddystudy.backend.auth

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiRuntimeException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PermissionCheckerTest {
    private val evaluator = FakePermissionEvaluator()
    private val checker = PermissionChecker(
        evaluator = evaluator,
    )

    @Test
    fun `permission check delegates to database backed evaluator`(): Unit = runBlocking {
        val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false, status = "ACTIVE")
        evaluator.results[Permissions.RECORD_UPDATE] = PermissionEvaluationResult.granted(Permissions.RECORD_UPDATE)

        checker.check(principal, listOf(Permissions.RECORD_UPDATE))

        assertThat(evaluator.calls).containsExactly(
            EvaluationCall(principal.userId, principal.deviceId, Permissions.RECORD_UPDATE),
        )
    }

    @Test
    fun `permission check forwards request evaluation context`(): Unit = runBlocking {
        val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false, status = "ACTIVE")
        evaluator.results[Permissions.RECORD_UPDATE] = PermissionEvaluationResult.granted(Permissions.RECORD_UPDATE)

        checker.check(
            principal,
            listOf(Permissions.RECORD_UPDATE),
            PermissionEvaluationContext(
                now = java.time.Instant.parse("2026-07-07T00:00:00Z"),
                appVersion = "1.2.3",
                sessionId = principal.sessionId,
                status = principal.status,
                anonymous = principal.anonymous,
            ),
        )

        assertThat(evaluator.contexts.single().appVersion).isEqualTo("1.2.3")
    }

    @Test
    fun `requirement failure throws the evaluator failure code`(): Unit = runBlocking {
        val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false, status = "ACTIVE")
        evaluator.results[Permissions.STUDY_CREATE] = PermissionEvaluationResult.denied(
            permissionCode = Permissions.STUDY_CREATE,
            failureCode = ApiErrorCode.TERMS_AGREEMENT_REQUIRED,
            reason = "Latest terms agreement is required.",
        )

        assertThatThrownBy {
            runBlocking { checker.check(principal, listOf(Permissions.STUDY_CREATE)) }
        }
            .isInstanceOf(ApiRuntimeException::class.java)
            .extracting("errorCode")
            .isEqualTo(ApiErrorCode.TERMS_AGREEMENT_REQUIRED)
    }

    private data class EvaluationCall(val userId: Long, val deviceId: String, val permissionCode: String)

    private class FakePermissionEvaluator : PermissionEvaluator {
        val results = mutableMapOf<String, PermissionEvaluationResult>()
        val calls = mutableListOf<EvaluationCall>()
        val contexts = mutableListOf<PermissionEvaluationContext>()

        override suspend fun evaluate(
            userId: Long,
            deviceId: String,
            permissionCode: String,
            context: PermissionEvaluationContext,
        ): PermissionEvaluationResult {
            calls += EvaluationCall(userId, deviceId, permissionCode)
            contexts += context
            return results[permissionCode] ?: PermissionEvaluationResult.denied(
                permissionCode = permissionCode,
                failureCode = ApiErrorCode.PERMISSION_DENIED,
                reason = "Permission denied.",
            )
        }
    }
}
