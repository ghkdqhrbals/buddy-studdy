package com.buddystudy.backend.auth.application.service

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
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
import com.buddystudy.backend.profile.application.service.ReferralRewardManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
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
    private val referralRewards: ReferralRewardManager,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun attachAppleIdentity(
        principal: Principal,
        identity: AppleIdentity,
        referralCode: String? = null,
        now: Instant,
    ): GoogleLoginResponse {
        val resolution = users.findByProviderAndProviderId("APPLE", identity.providerId)
            ?.let { UserResolution(it, isNewAccount = false) }
            ?: createUser(
                provider = UserProvider.APPLE,
                providerId = identity.providerId,
                email = identity.email,
                now = now,
            )

        val referralAttributed = referralRewards.capturePendingAttribution(resolution.user.id, referralCode, now)
        return attachAuthenticatedUser(principal, resolution.user, resolution.isNewAccount, referralAttributed, now)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun attachGoogleIdentity(
        principal: Principal,
        identity: GoogleIdentity,
        referralCode: String? = null,
        now: Instant,
    ): GoogleLoginResponse {
        val resolution = users.findByProviderAndProviderId("GOOGLE", identity.providerId)
            ?.let { UserResolution(it, isNewAccount = false) }
            ?: createUser(
                provider = UserProvider.GOOGLE,
                providerId = identity.providerId,
                email = identity.email,
                now = now,
            )

        val referralAttributed = referralRewards.capturePendingAttribution(resolution.user.id, referralCode, now)
        return attachAuthenticatedUser(principal, resolution.user, resolution.isNewAccount, referralAttributed, now)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun attachEmailIdentity(
        principal: Principal,
        email: String,
        passwordHash: String,
        referralCode: String? = null,
        now: Instant,
    ): GoogleLoginResponse {
        val resolution = users.findByEmailAndProvider(email, "EMAIL")
            ?.let { UserResolution(it, isNewAccount = false) }
            ?: createUser(
                provider = UserProvider.EMAIL,
                providerId = email,
                email = email,
                passwordHash = passwordHash,
                now = now,
            )
        if (resolution.user.passwordHash != passwordHash) {
            throw ApiException(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.AUTH_INVALID_EMAIL_CREDENTIALS,
                "Invalid email or password.",
            )
        }
        val referralAttributed = referralRewards.capturePendingAttribution(resolution.user.id, referralCode, now)
        return attachAuthenticatedUser(principal, resolution.user, resolution.isNewAccount, referralAttributed, now)
    }

    private suspend fun createUser(
        provider: UserProvider,
        providerId: String,
        email: String,
        passwordHash: String? = null,
        now: Instant,
    ): UserResolution {
        repeat(DISPLAY_NAME_ATTEMPTS) {
            try {
                return UserResolution(
                    user = users.save(
                        UserEntity(
                            provider = provider,
                            providerId = providerId,
                            email = email,
                            passwordHash = passwordHash,
                            status = UserStatus.PENDING_TERMS,
                            displayName = displayNames.next(),
                            avatarColorSeed = "avatar-color-mint",
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                    isNewAccount = true,
                )
            } catch (duplicate: DataIntegrityViolationException) {
                users.findByProviderAndProviderId(provider.name, providerId)?.let {
                    return UserResolution(it, isNewAccount = false)
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
        isNewAccount: Boolean,
        referralAttributed: Boolean,
        now: Instant,
    ): GoogleLoginResponse {
        roles.grantRoleIfMissing(user.id, Roles.REGISTERED_USER)
        val device = sessions.device(principal.deviceId)
        device.apply(Account.of(user.toAccountUser(), device.toAccountDevice()).attachDevice(now))
        devices.save(device)
        val session = sessions.saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false, user.status.name)
        return GoogleLoginResponse(
            profile = user.toProfile(),
            accessToken = token.first,
            accessTokenExpiresAt = token.second,
            isNewAccount = isNewAccount,
            referralAttributed = referralAttributed,
        )
    }

    private fun UserEntity.toAccountUser() = AccountUser(id = id, status = status.name)

    private fun DeviceEntity.toAccountDevice() = AccountDevice(deviceId = deviceId, userId = userId)

    private fun DeviceEntity.apply(attachment: DeviceAttachment) {
        userId = attachment.userId
        updatedAt = attachment.updatedAt
    }

    private companion object {
        const val DISPLAY_NAME_ATTEMPTS = 12
    }

    private data class UserResolution(
        val user: UserEntity,
        val isNewAccount: Boolean,
    )
}
