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
import com.buddystudy.backend.auth.application.port.outbound.AppleIdentity
import com.buddystudy.backend.auth.application.port.outbound.GoogleIdentity
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.profile.application.model.toProfile
import org.springframework.dao.DataIntegrityViolationException
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
    private val displayNames: RandomDisplayNameProvider,
) {
    @Transactional
    suspend fun attachAppleIdentity(
        principal: Principal,
        identity: AppleIdentity,
        now: Instant,
    ): GoogleLoginResponse {
        val user = users.findByProviderAndProviderId("APPLE", identity.providerId)
            ?: createUser(
                provider = "APPLE",
                providerId = identity.providerId,
                email = identity.email,
                now = now,
            )

        return attachAuthenticatedUser(principal, user, now)
    }

    @Transactional
    suspend fun attachGoogleIdentity(
        principal: Principal,
        identity: GoogleIdentity,
        now: Instant,
    ): GoogleLoginResponse {
        val user = users.findByProviderAndProviderId("GOOGLE", identity.providerId)
            ?: createUser(
                provider = "GOOGLE",
                providerId = identity.providerId,
                email = identity.email,
                now = now,
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
            ?: createUser(
                provider = "EMAIL",
                providerId = email,
                email = email,
                passwordHash = passwordHash,
                now = now,
            )
        if (user.passwordHash != passwordHash) {
            throw ApiException(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.AUTH_INVALID_EMAIL_CREDENTIALS,
                "Invalid email or password.",
            )
        }
        return attachAuthenticatedUser(principal, user, now)
    }

    private suspend fun createUser(
        provider: String,
        providerId: String,
        email: String,
        passwordHash: String? = null,
        now: Instant,
    ): UserEntity {
        repeat(DISPLAY_NAME_ATTEMPTS) {
            try {
                return users.save(
                    UserEntity(
                        provider = provider,
                        providerId = providerId,
                        email = email,
                        passwordHash = passwordHash,
                        status = "PENDING_TERMS",
                        displayName = displayNames.next(),
                        avatarColorSeed = "avatar-color-mint",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } catch (duplicate: DataIntegrityViolationException) {
                users.findByProviderAndProviderId(provider, providerId)?.let {
                    return it
                }
            }
        }
        throw ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            ApiErrorCode.SERVER_BUSY,
            "Could not reserve a unique display name.",
        )
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

    private companion object {
        const val DISPLAY_NAME_ATTEMPTS = 12
    }
}
