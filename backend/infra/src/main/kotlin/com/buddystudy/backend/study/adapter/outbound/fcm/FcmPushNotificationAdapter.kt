package com.buddystudy.backend.study.adapter.outbound.fcm

import com.buddystudy.backend.study.application.port.outbound.FcmQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushMessageType
import com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushQuestionSender
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "buddystudy.fcm", name = ["enabled"], havingValue = "true")
class FcmPushNotificationAdapter : PushQuestionSender {
    override val type: PushMessageType = PushMessageType.FCM

    override suspend fun sendQuestion(message: PushQuestionMessage) {
        require(message is FcmQuestionMessage) { "FCM adapter cannot send ${message.type} messages." }
        throw UnsupportedOperationException("FCM push sender is not implemented yet.")
    }
}
