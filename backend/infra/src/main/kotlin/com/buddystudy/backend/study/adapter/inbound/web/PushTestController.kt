package com.buddystudy.backend.study.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.common.adapter.inbound.web.PermissionWebGuard
import com.buddystudy.backend.study.adapter.inbound.web.dto.PushTestRequest
import com.buddystudy.backend.study.application.model.PushTestCommand
import com.buddystudy.backend.study.application.model.PushTestResponse
import com.buddystudy.backend.study.application.port.inbound.SendTestPushUseCase
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
    private val permissionGuard: PermissionWebGuard,
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
    suspend fun send(
        @RequestBody(required = false) body: PushTestRequest?,
        authentication: Authentication,
    ): PushTestResponse {
        permissionGuard.check(authentication, Permissions.TEST_PUSH_SEND)
        return pushTest.send(body ?: PushTestRequest(), authentication)
    }

    @Operation(
        summary = "Publish a test push event",
        description = "Publishes one test event to the push stream. The push listener will consume it and send the notification if the device has a valid push token.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Test push event published."),
        ApiResponse(responseCode = "401", description = "Authentication required."),
    )
    @PostMapping("/test/push-event")
    suspend fun publishEvent(
        @RequestBody(required = false) body: PushTestRequest?,
        authentication: Authentication,
    ): PushTestResponse {
        permissionGuard.check(authentication, Permissions.TEST_PUSH_SEND)
        return pushTest.publishEvent(body ?: PushTestRequest(), authentication)
    }
}

interface PushTestWebPort {
    suspend fun send(body: PushTestRequest, authentication: Authentication): PushTestResponse
    suspend fun publishEvent(body: PushTestRequest, authentication: Authentication): PushTestResponse
}

@Component
class PushTestWebAdapter(
    private val sendTestPush: SendTestPushUseCase,
) : PushTestWebPort {
    override suspend fun send(body: PushTestRequest, authentication: Authentication) =
        sendTestPush.sendTestPush(authentication.principalOrThrow(), body.toCommand())

    override suspend fun publishEvent(body: PushTestRequest, authentication: Authentication) =
        sendTestPush.publishTestPushEvent(authentication.principalOrThrow(), body.toCommand())
}

private fun PushTestRequest.toCommand() = PushTestCommand(
    title = title ?: "BuddyStudy",
    body = body ?: "BuddyStudy test push.",
    topic = topic ?: "Test",
    recordId = recordId ?: "test",
    studyId = studyId,
    difficultyLevel = difficultyLevel ?: 1,
    language = language ?: "ko",
    sound = sound ?: "default",
    deepLink = deepLink ?: "buddystudy://test-push",
)
