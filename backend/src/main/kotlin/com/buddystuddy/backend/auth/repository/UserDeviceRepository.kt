package com.buddystuddy.backend.auth.repository

import com.buddystuddy.backend.domain.UserDeviceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserDeviceRepository : JpaRepository<UserDeviceEntity, Long> {
    fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?
}
