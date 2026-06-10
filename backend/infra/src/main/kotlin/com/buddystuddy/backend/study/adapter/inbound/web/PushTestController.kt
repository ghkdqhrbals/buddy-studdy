package com.buddystuddy.backend.study.adapter.inbound.web

import com.buddystuddy.backend.auth.application.permission.Permissions
import com.buddystuddy.backend.auth.application.permission.RequirePermission
import com.buddystuddy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystuddy.backend.study.adapter.inbound.web.dto.PushTestRequest
import com.buddystuddy.backend.study.application.model.PushTestCommand
import com.buddystuddy.backend.study.application.port.inbound.SendTestPushUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Test", description = "Authenticated development/test utilities.")
@RequirePermission(Permissions.TEST_PUSH_SEND)
class PushTestController(
    private val pushTest: PushTestWebPort,
) {
    @Operation(
        summary = "Send a test push notification",
        description = "Sends one APNs test notification to the authenticated device using the APNs token saved by /api/v1/push-token.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Test push sent."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
        ApiResponse(responseCode = "422", description = "The authenticated device has no APNs token."),
    )
    @PostMapping("/test/push")
    fun send(
        @RequestBody(required = false) body: PushTestRequest?,
        authentication: Authentication,
    ) = pushTest.send(body ?: PushTestRequest(), authentication)
}

interface PushTestWebPort {
    fun send(body: PushTestRequest, authentication: Authentication): Any
}

@Component
class PushTestWebAdapter(
    private val sendTestPush: SendTestPushUseCase,
) : PushTestWebPort {
    override fun send(body: PushTestRequest, authentication: Authentication) =
        sendTestPush.sendTestPush(authentication.principalOrThrow(), body.toCommand())
}

private fun PushTestRequest.toCommand() = PushTestCommand(
    title = title ?: "BuddyStuddy",
    body = body ?: "BuddyStuddy test push.",
    topic = topic ?: "Test",
    recordId = recordId ?: "test",
    sound = sound ?: "default",
    deepLink = deepLink ?: "buddystuddy://test-push",
)
