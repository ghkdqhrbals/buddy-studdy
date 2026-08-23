package com.buddystudy.backend.profile.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.model.AccessTokenResponse
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.model.AvatarCatalogResponse
import com.buddystudy.backend.profile.application.model.UserProfileResponse
import com.buddystudy.backend.profile.application.port.outbound.StoredProfilePhoto

interface ProfileUseCase {
    suspend fun profile(principal: Principal): UserProfileResponse
    suspend fun avatarCatalog(principal: Principal): AvatarCatalogResponse
    suspend fun updateAvatar(principal: Principal, command: AvatarUpdateCommand): UserProfileResponse
    suspend fun updateProfile(principal: Principal, command: ProfileUpdateCommand): UserProfileResponse
    suspend fun profilePhoto(userId: Long): StoredProfilePhoto
    suspend fun withdrawProfile(principal: Principal): AccessTokenResponse
}

interface AccountWithdrawalCleanupUseCase {
    suspend fun cleanup(event: AccountWithdrawnEvent)
}
