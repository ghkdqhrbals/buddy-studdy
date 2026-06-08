package com.buddystuddy.backend.profile.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.common.service.BackendSupportService
import com.buddystuddy.backend.dto.ProfileUpdateRequest
import com.buddystuddy.backend.dto.UserProfileResponse
import com.buddystuddy.backend.dto.toProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ProfileService(
    private val support: BackendSupportService,
) {
    @Transactional(readOnly = true)
    fun profile(principal: Principal): UserProfileResponse = support.user(principal.userId).toProfile()

    @Transactional
    fun updateProfile(principal: Principal, payload: ProfileUpdateRequest): UserProfileResponse {
        val user = support.user(principal.userId)
        payload.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { user.displayName = it.take(120) }
        payload.bio?.let { user.bio = it.take(500) }
        payload.avatarSymbolName?.let { user.avatarSymbolName = it.take(64) }
        payload.avatarColorSeed?.let { user.avatarColorSeed = it.take(64) }
        payload.pageAccess?.let { user.allowPublicQuestions = it.publicQuestions }
        user.updatedAt = Instant.now()
        return user.toProfile()
    }
}
