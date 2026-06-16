package com.buddystuddy.backend.auth.adapter.outbound.persistence

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.auth.domain.entity.DeviceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceRepository : JpaRepository<DeviceEntity, Long>, DevicePort {
    override fun findByDeviceId(deviceId: String): DeviceEntity?
    override fun findAllByUserId(userId: Long): List<DeviceEntity>
}
