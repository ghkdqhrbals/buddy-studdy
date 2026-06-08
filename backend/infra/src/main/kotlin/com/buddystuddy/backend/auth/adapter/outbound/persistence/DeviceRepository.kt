package com.buddystuddy.backend.auth.adapter.outbound.persistence

import com.buddystuddy.backend.auth.application.port.outbound.DevicePort
import com.buddystuddy.domain.DeviceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceRepository : JpaRepository<DeviceEntity, Long>, DevicePort {
    override fun findByDeviceId(deviceId: String): DeviceEntity?
}
