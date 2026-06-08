package com.buddystuddy.backend.auth.domain

import com.buddystuddy.backend.domain.DeviceEntity
import com.buddystuddy.backend.domain.UserEntity
import java.time.Instant

class Account private constructor(
    val user: UserEntity,
    val device: DeviceEntity,
) {
    fun attachDevice(now: Instant = Instant.now()) {
        device.userId = user.id
        device.updatedAt = now
    }

    fun updatePushToken(apnsToken: String, apnsEnvironment: String, now: Instant = Instant.now()) {
        device.apnsToken = apnsToken
        device.apnsEnvironment = apnsEnvironment
        device.updatedAt = now
    }

    companion object {
        fun of(user: UserEntity, device: DeviceEntity) = Account(user, device)
    }
}
