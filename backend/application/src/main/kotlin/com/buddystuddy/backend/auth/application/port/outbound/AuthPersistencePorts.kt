package com.buddystuddy.backend.auth.application.port.outbound

import com.buddystuddy.domain.DeviceEntity
import com.buddystuddy.domain.UserDeviceEntity
import com.buddystuddy.domain.UserEntity
import java.util.Optional

interface UserPort {
    fun save(entity: UserEntity): UserEntity
    fun findById(id: Long): Optional<UserEntity>
    fun findAllById(ids: Iterable<Long>): MutableList<UserEntity>
    fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    fun findByEmailAndProvider(email: String, provider: String): UserEntity?
}

interface DevicePort {
    fun save(entity: DeviceEntity): DeviceEntity
    fun findByDeviceId(deviceId: String): DeviceEntity?
}

interface UserDevicePort {
    fun save(entity: UserDeviceEntity): UserDeviceEntity
    fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?
}
