package com.buddystuddy.backend.profile.application.model

import com.buddystuddy.account.domain.entity.UserEntity

fun UserEntity.toProfile() = UserProfileResponse(
    id = id,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    pageAccess = CommunityPageAccess(publicQuestions = allowPublicQuestions),
)
