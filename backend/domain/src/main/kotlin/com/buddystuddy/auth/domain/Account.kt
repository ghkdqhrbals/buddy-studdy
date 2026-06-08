package com.buddystuddy.auth.domain

import java.time.Instant

class Account private constructor(
    val user: AccountUser,
    val device: AccountDevice,
) {
    fun attachDevice(now: Instant = Instant.now()) = DeviceAttachment(
        userId = user.id,
        updatedAt = now,
    )

    fun updatePushToken(apnsToken: String, apnsEnvironment: String, now: Instant = Instant.now()) = PushTokenUpdate(
        apnsToken = apnsToken,
        apnsEnvironment = apnsEnvironment,
        updatedAt = now,
    )

    companion object {
        fun of(user: AccountUser, device: AccountDevice) = Account(user, device)
    }
}

data class AccountUser(
    val id: Long,
    val status: String,
)

data class AccountDevice(
    val deviceId: String,
    val userId: Long?,
)

data class DeviceAttachment(
    val userId: Long,
    val updatedAt: Instant,
)

data class PushTokenUpdate(
    val apnsToken: String,
    val apnsEnvironment: String,
    val updatedAt: Instant,
)
