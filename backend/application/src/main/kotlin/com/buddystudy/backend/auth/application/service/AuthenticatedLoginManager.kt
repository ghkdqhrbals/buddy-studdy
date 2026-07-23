package com.buddystudy.backend.auth.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.auth.domain.Account
import com.buddystudy.auth.domain.AccountDevice
import com.buddystudy.auth.domain.AccountUser
import com.buddystudy.auth.domain.DeviceAttachment
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.auth.application.model.GoogleLoginResponse
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentity
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.profile.application.model.toProfile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AuthenticatedLoginManager(
    private val users: UserPort,
    private val devices: DevicePort,
    private val sessions: AccountSessionManager,
    private val roles: RoleAssignmentPort,
    private val tokenService: TokenProvider,
) {
    @Transactional
    suspend fun attachGoogleIdentity(
        principal: Principal,
        identity: GoogleIdentity,
        now: Instant,
    ): GoogleLoginResponse {
        val displayName = identity.name
            ?.takeIf(String::isNotBlank)
            ?: identity.email.substringBefore("@").ifBlank { "Buddy" }
        val user = users.findByProviderAndProviderId("GOOGLE", identity.providerId)
            ?: users.save(
                UserEntity(
                    provider = "GOOGLE",
                    providerId = identity.providerId,
                    email = identity.email,
                    status = "PENDING_TERMS",
                    displayName = displayName,
                    avatarColorSeed = "avatar-color-mint",
                    createdAt = now,
                    updatedAt = now,
                ),
            )

        return attachAuthenticatedUser(principal, user, now)
    }

    @Transactional
    suspend fun attachEmailIdentity(
        principal: Principal,
        email: String,
        passwordHash: String,
        now: Instant,
    ): GoogleLoginResponse {
        val user = users.findByEmailAndProvider(email, "EMAIL")
            ?: users.save(
                UserEntity(
                    provider = "EMAIL",
                    providerId = email,
                    email = email,
                    passwordHash = passwordHash,
                    status = "PENDING_TERMS",
                    displayName = email.substringBefore("@"),
                    avatarColorSeed = "avatar-color-mint",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        if (user.passwordHash != passwordHash) {
            throw ApiException(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS,
                "Invalid email or password.",
            )
        }
        return attachAuthenticatedUser(principal, user, now)
    }

    private suspend fun attachAuthenticatedUser(
        principal: Principal,
        user: UserEntity,
        now: Instant,
    ): GoogleLoginResponse {
        roles.grantRoleIfMissing(user.id, Roles.REGISTERED_USER)
        val device = sessions.device(principal.deviceId)
        device.apply(Account.of(user.toAccountUser(), device.toAccountDevice()).attachDevice(now))
        devices.save(device)
        val session = sessions.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false, user.status)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    private fun UserEntity.toAccountUser() = AccountUser(id = id, status = status)

    private fun DeviceEntity.toAccountDevice() = AccountDevice(deviceId = deviceId, userId = userId)

    private fun DeviceEntity.apply(attachment: DeviceAttachment) {
        userId = attachment.userId
        updatedAt = attachment.updatedAt
    }
}
