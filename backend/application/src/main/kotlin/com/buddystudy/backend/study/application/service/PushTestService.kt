package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.model.PushTestCommand
import com.buddystudy.backend.study.application.model.PushTestResponse
import com.buddystudy.backend.study.application.port.inbound.SendTestPushUseCase
import com.buddystudy.backend.study.application.port.outbound.ApnsAlert
import com.buddystudy.backend.study.application.port.outbound.ApnsAps
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.ApnsQuestionPayload
import com.buddystudy.backend.study.application.port.outbound.PushMessageType
import com.buddystudy.backend.study.application.port.outbound.PushNotificationPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PushTestService(
    private val devices: DevicePort,
    private val pushNotifications: PushNotificationPort,
    private val notifications: PublishNotificationUseCase,
) : SendTestPushUseCase {
    override suspend fun sendTestPush(principal: Principal, command: PushTestCommand): PushTestResponse {
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
            environment = device.apnsEnvironment.databaseValue,
            payload = ApnsQuestionPayload(
                aps = ApnsAps(
                    alert = ApnsAlert(
                        title = command.title.ifBlank { "BuddyStudy" },
                        body = command.body.ifBlank { "BuddyStudy test push." },
                    ),
                    sound = command.sound.ifBlank { "default" },
                ),
                deepLink = command.deepLink.ifBlank { "buddystudy://test-push" },
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

    override suspend fun publishTestPushEvent(principal: Principal, command: PushTestCommand): PushTestResponse {
        val recordId = command.recordId.toLongOrNull() ?: 0L
        val topic = command.topic.ifBlank { "Test" }
        val published = notifications.publish(
            NotificationRequestCommand(
                eventId = "push-test-${UUID.randomUUID()}",
                userId = principal.userId,
                deviceId = principal.deviceId,
                type = "ADMIN_MESSAGE",
                title = command.title.ifBlank { "BuddyStudy" },
                body = command.body.ifBlank { "BuddyStudy test push." },
                threadType = "admin_message",
                threadId = recordId.toString(),
                deepLink = command.deepLink.ifBlank { "buddystudy://test-push" },
                metadataJson = JsonMapperProvider.mapper.writeValueAsString(
                    mapOf(
                        "recordId" to recordId,
                        "studyId" to command.studyId,
                        "topic" to topic,
                        "difficultyLevel" to command.difficultyLevel.coerceIn(1, 10),
                        "language" to command.language.ifBlank { "ko" },
                        "sound" to command.sound.ifBlank { "default" },
                        "intervalMinutes" to 0,
                    ),
                ),
                shouldPush = true,
            ),
        )
        return PushTestResponse(
            sent = published,
            provider = "NOTIFICATION_STREAM",
            deviceId = principal.deviceId,
            topic = topic,
            recordId = recordId.toString(),
        )
    }
}
