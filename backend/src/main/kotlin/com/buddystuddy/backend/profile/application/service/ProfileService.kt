package com.buddystuddy.backend.profile.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.common.application.service.BackendSupportService
import com.buddystuddy.backend.profile.application.model.UserProfileResponse
import com.buddystuddy.backend.profile.application.model.toProfile
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUpdateCommand
import com.buddystuddy.backend.profile.application.port.inbound.ProfileUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProfileService(
    private val support: BackendSupportService,
) : ProfileUseCase {
    @Transactional(readOnly = true)
    override fun profile(principal: Principal): UserProfileResponse = support.user(principal.userId).toProfile()

    @Transactional
    override fun updateProfile(principal: Principal, command: ProfileUpdateCommand): UserProfileResponse {
        val user = support.user(principal.userId)
        command.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { user.displayName = it.take(120) }
        command.bio?.let { user.bio = it.take(500) }
        command.avatarSymbolName?.let { user.avatarSymbolName = it.take(64) }
        command.avatarColorSeed?.let { user.avatarColorSeed = it.take(64) }
        command.pageAccess?.let { user.allowPublicQuestions = it.publicQuestions }
        user.updatedAt = Instant.now()
        return user.toProfile()
    }
}
