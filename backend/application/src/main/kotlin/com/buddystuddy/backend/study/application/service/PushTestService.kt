package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.study.application.model.PushTestCommand
import com.buddystuddy.backend.study.application.model.PushTestResponse
import com.buddystuddy.backend.study.application.port.inbound.SendTestPushUseCase
import com.buddystuddy.backend.study.application.port.outbound.ApnsAlert
import com.buddystuddy.backend.study.application.port.outbound.ApnsAps
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystuddy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushPublishPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionPushRequest
import com.buddystuddy.backend.study.application.port.outbound.PushMessageType
import com.buddystuddy.backend.study.application.port.outbound.PushNotificationPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PushTestService(
    private val devices: DevicePort,
    private val pushNotifications: PushNotificationPort,
    private val pushEvents: QuestionPushPublishPort,
) : SendTestPushUseCase {
    override fun sendTestPush(principal: Principal, command: PushTestCommand): PushTestResponse {
        val device = devices.findByDeviceId(principal.deviceId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.DEVICE_NOT_FOUND, "Device not found.")
        val token = device.apnsToken.trim()
        if (token.isBlank()) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, "APNs token is missing for this device.")
        }
        val message = ApnsQuestionMessage(
            recordId = command.recordId.ifBlank { "test" },
            topic = command.topic.ifBlank { "Test" },
            token = token,
            environment = device.apnsEnvironment.ifBlank { "production" },
            payload = ApnsQuestionPayload(
                aps = ApnsAps(
                    alert = ApnsAlert(
                        title = command.title.ifBlank { "BuddyStuddy" },
                        body = command.body.ifBlank { "BuddyStuddy test push." },
                    ),
                    sound = command.sound.ifBlank { "default" },
                ),
                deepLink = command.deepLink.ifBlank { "buddystuddy://test-push" },
            ),
        )
        pushNotifications.sendQuestion(message)
        return PushTestResponse(
            sent = true,
            provider = PushMessageType.APNS.name,
            deviceId = principal.deviceId,
            topic = message.topic,
            recordId = message.recordId,
        )
    }

    override fun publishTestPushEvent(principal: Principal, command: PushTestCommand): PushTestResponse {
        val recordId = command.recordId.toLongOrNull() ?: 0L
        val request = QuestionPushRequest(
            recordId = recordId,
            studyId = command.studyId,
            deviceId = principal.deviceId,
            userId = principal.userId,
            question = command.body.ifBlank { "BuddyStuddy test push." },
            expectedAnswerHint = null,
            topic = command.topic.ifBlank { "Test" },
            difficultyLevel = command.difficultyLevel.coerceIn(1, 10),
            language = command.language.ifBlank { "ko" },
            sound = command.sound.ifBlank { "default" },
            intervalMinutes = 0,
            title = command.title.ifBlank { "BuddyStuddy" },
            body = command.body.ifBlank { "BuddyStuddy test push." },
            deepLink = command.deepLink.ifBlank { "buddystuddy://test-push" },
            createdAt = Instant.now(),
        )
        val published = pushEvents.publishPush(request)
        return PushTestResponse(
            sent = published,
            provider = "PUSH_STREAM",
            deviceId = principal.deviceId,
            topic = request.topic,
            recordId = request.recordId.toString(),
        )
    }
}
