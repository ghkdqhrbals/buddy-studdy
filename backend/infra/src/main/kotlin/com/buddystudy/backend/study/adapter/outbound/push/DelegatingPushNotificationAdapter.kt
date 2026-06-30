package com.buddystudy.backend.study.adapter.outbound.push

import com.buddystudy.backend.study.application.port.outbound.PushNotificationPort
import com.buddystudy.backend.study.application.port.outbound.PushQuestionMessage
import com.buddystudy.backend.study.application.port.outbound.PushQuestionSender
import org.springframework.stereotype.Component

@Component
class DelegatingPushNotificationAdapter(
    senders: List<PushQuestionSender>,
) : PushNotificationPort {
    private val sendersByType = senders.associateBy { it.type }

    override fun sendQuestion(message: PushQuestionMessage) {
        val sender = sendersByType[message.type]
            ?: throw IllegalStateException("No push sender configured for ${message.type}.")
        sender.sendQuestion(message)
    }
}
