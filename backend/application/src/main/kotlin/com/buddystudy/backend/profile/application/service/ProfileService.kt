package com.buddystudy.backend.profile.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
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

    private fun user(id: Long) = users.findById(id).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
    }
}
