package com.buddystudy.backend.profile.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.TokenProvider
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.auth.application.permission.Roles
import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.auth.application.port.outbound.DevicePort
import com.buddystudy.backend.auth.application.port.outbound.RoleAssignmentPort
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.auth.application.service.AccountSessionManager
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.profile.application.model.toProfile
import com.buddystudy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystudy.backend.profile.application.port.inbound.ProfileUseCase
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProfileService(
    private val users: UserPort,
    private val devices: DevicePort,
    private val sessions: AccountSessionManager,
    private val roles: RoleAssignmentPort,
    private val tokenService: TokenProvider,
    private val accountDeletion: AccountDeletionPort,
) : ProfileUseCase {
    @Transactional(readOnly = true)
    override fun profile(principal: Principal): UserProfileResponse = user(principal.userId).toProfile()

    @Transactional
    override fun updateProfile(principal: Principal, command: ProfileUpdateCommand): UserProfileResponse {
        val user = user(principal.userId)
        command.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { user.displayName = it.take(120) }
        command.bio?.let { user.bio = it.take(500) }
        command.avatarSymbolName?.let { user.avatarSymbolName = it.take(64) }
        command.avatarColorSeed?.let { user.avatarColorSeed = it.take(64) }
        user.updatedAt = Instant.now()
        return user.toProfile()
    }

    @Transactional
    override fun withdrawProfile(principal: Principal): AccessTokenResponse {
        if (principal.anonymous) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Account deletion requires an active login.")
        }
        val now = Instant.now()
        accountDeletion.deleteAccountData(principal.userId, principal.deviceId, now)
        val device = sessions.device(principal.deviceId)
        val anonymousUser = sessions.ensureAnonymousUser(device)
        devices.save(device)
        roles.grantRoleIfMissing(anonymousUser.id, Roles.ANONYMOUS_USER)
        val session = sessions.saveSession(anonymousUser.id, device.deviceId, now, null)
        val token = tokenService.create(anonymousUser.id, device.deviceId, session.id, true, anonymousUser.status)
        return AccessTokenResponse(token.first, token.second)
    }

    private fun user(id: Long) = users.findById(id).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
    }
}
