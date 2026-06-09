package com.buddystuddy.backend.auth.application.port.outbound

import com.buddystuddy.auth.domain.entity.DeviceEntity
import com.buddystuddy.auth.domain.entity.UserDeviceEntity
import com.buddystuddy.account.domain.entity.UserEntity
import java.time.Duration
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

interface EmailVerificationCodePort {
    fun save(email: String, code: String, ttl: Duration)
    fun consume(email: String, code: String): Boolean
}

interface EmailVerificationSenderPort {
    fun send(email: String, code: String, ttl: Duration)
}
