package com.buddystuddy.backend.auth.repository

import com.buddystuddy.backend.domain.DeviceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceRepository : JpaRepository<DeviceEntity, Long> {
    fun findByDeviceId(deviceId: String): DeviceEntity?
}
