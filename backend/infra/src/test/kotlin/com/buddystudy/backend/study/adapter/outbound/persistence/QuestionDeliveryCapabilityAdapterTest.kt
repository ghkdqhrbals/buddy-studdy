package com.buddystudy.backend.study.adapter.outbound.persistence

import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class QuestionDeliveryCapabilityAdapterTest {
    @Test
    fun `schedule requires all active devices and rejects unknown legacy or missing ownership`() = runBlocking<Unit> {
        val devices = mock(DevicePort::class.java)
        val sessions = mock(UserDevicePort::class.java)
        val adapter = QuestionDeliveryCapabilityAdapter(devices, sessions)
        `when`(sessions.findActiveByUserId(7)).thenReturn(emptyList())
        assertThat(adapter.supportsMultipleChoice(7)).isFalse()
        `when`(sessions.findActiveByUserId(7)).thenReturn(listOf(UserDeviceEntity(userId = 7, deviceId = "new"), UserDeviceEntity(userId = 7, deviceId = "old")))
        val upgraded = DeviceEntity(userId = 7, deviceId = "new", questionApiVersion = 3)
        val legacy = DeviceEntity(userId = 7, deviceId = "old")
        `when`(devices.findAllByUserId(7)).thenReturn(listOf(upgraded, legacy))
        assertThat(adapter.supportsMultipleChoice(7)).isFalse()
        legacy.questionApiVersion = 3
        assertThat(adapter.supportsMultipleChoice(7)).isTrue()
        `when`(devices.findAllByUserId(7)).thenReturn(listOf(upgraded))
        assertThat(adapter.supportsMultipleChoice(7)).isFalse()
        `when`(sessions.findActiveByUserId(7)).thenReturn(listOf(UserDeviceEntity(userId = 7, deviceId = "new")))
        assertThat(adapter.supportsMultipleChoice(7)).isTrue()
    }
}
