package com.buddystuddy.backend.auth.adapter.outbound.persistence

import com.buddystuddy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystuddy.backend.domain.UserDeviceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserDeviceRepository : JpaRepository<UserDeviceEntity, Long>, UserDevicePort {
    override fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    override fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?
}
