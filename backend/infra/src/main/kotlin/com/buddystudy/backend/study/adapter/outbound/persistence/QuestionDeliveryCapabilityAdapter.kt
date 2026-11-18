package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.backend.study.application.port.outbound.QuestionDeliveryCapabilityPort
import org.springframework.stereotype.Component

@Component
class QuestionDeliveryCapabilityAdapter(
    private val devices: DevicePort,
    private val sessions: UserDevicePort,
) : QuestionDeliveryCapabilityPort {
    override suspend fun supportsMultipleChoice(userId: Long): Boolean {
        val activeIds = sessions.findActiveByUserId(userId).filter { it.isActive() }.map { it.deviceId }.toSet()
        if (activeIds.isEmpty()) return false
        val owned = devices.findAllByUserId(userId).filter { it.userId == userId && it.deviceId in activeIds }
        return owned.map { it.deviceId }.toSet() == activeIds && owned.all { it.questionApiVersion >= 3 }
    }
}
