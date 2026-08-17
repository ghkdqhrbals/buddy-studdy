package com.buddystudy.backend.auth.application.port.outbound

import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import com.buddystudy.account.domain.entity.UserEntity
import java.time.Duration
import java.time.Instant

interface UserPort {
    suspend fun save(entity: UserEntity): UserEntity
    suspend fun findById(id: Long): UserEntity?
    suspend fun findAllById(ids: Iterable<Long>): List<UserEntity>
    suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity?
    suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity?
}

interface DevicePort {
    suspend fun save(entity: DeviceEntity): DeviceEntity
    suspend fun findByDeviceId(deviceId: String): DeviceEntity?
    suspend fun findByInstallationKeyHash(installationKeyHash: String): DeviceEntity?
    suspend fun findAllByUserId(userId: Long): List<DeviceEntity>
}

interface AccountDeletionPort {
    suspend fun beginWithdrawal(userId: Long, now: Instant): AccountWithdrawalSnapshot
    suspend fun deleteAccountData(
        userId: Long,
        deviceIds: List<String>,
        withdrawnAt: Instant,
    )
}

data class AccountWithdrawalSnapshot(
    val deviceIds: List<String>,
)

interface UserDevicePort {
    suspend fun save(entity: UserDeviceEntity): UserDeviceEntity
    suspend fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    suspend fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?
    suspend fun findActiveByUserId(userId: Long): List<UserDeviceEntity>
    suspend fun hasActiveSession(userId: Long, deviceId: String): Boolean
    suspend fun revokeOtherActiveSessionsForDevice(deviceId: String, userId: Long, revokedAt: Instant): Int
}

interface EmailVerificationCodePort {
    suspend fun save(email: String, code: String, ttl: Duration)
    suspend fun consume(email: String, code: String): Boolean
}

interface EmailVerificationSenderPort {
    suspend fun send(email: String, code: String, ttl: Duration)
}
