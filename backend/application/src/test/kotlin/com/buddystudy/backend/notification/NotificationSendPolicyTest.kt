package com.buddystudy.backend.notification

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationResult
import com.buddystudy.backend.auth.application.permission.PermissionEvaluator
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.service.NotificationSendPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationSendPolicyTest {
    private val evaluator = FakePermissionEvaluator()
    private val policy = NotificationSendPolicy(evaluator)

    @Test
    fun `marketing push is skipped when marketing notification permission is denied`(): Unit = runBlocking {
        evaluator.result = PermissionEvaluationResult.denied(
            permissionCode = "notification:receive-marketing",
            failureCode = com.buddystudy.backend.common.application.error.ApiErrorCode.TERMS_AGREEMENT_REQUIRED,
        )

        val allowed = policy.canSendPush(
            NotificationRequestCommand(
                eventId = "evt-1",
                userId = 7,
                deviceId = "dev-1",
                type = "MARKETING",
                title = "BuddyStudy",
                body = "hello",
                shouldPush = true,
            )
        )

        assertThat(allowed).isFalse()
        assertThat(evaluator.calls.single()).isEqualTo("notification:receive-marketing")
    }

    @Test
    fun `study question push uses informational notification permission`(): Unit = runBlocking {
        evaluator.result = PermissionEvaluationResult.granted("notification:receive-info")

        val allowed = policy.canSendPush(
            NotificationRequestCommand(
                eventId = "evt-2",
                userId = 7,
                deviceId = "dev-1",
                type = "STUDY_QUESTION",
                title = "BuddyStudy",
                body = "question",
                shouldPush = true,
            )
        )

        assertThat(allowed).isTrue()
        assertThat(evaluator.calls.single()).isEqualTo("notification:receive-info")
    }

    private class FakePermissionEvaluator : PermissionEvaluator {
        var result = PermissionEvaluationResult.granted("notification:receive-info")
        val calls = mutableListOf<String>()

        override suspend fun evaluate(principal: Principal, permissionCode: String): PermissionEvaluationResult {
            calls += permissionCode
            return result
        }
    }
}
