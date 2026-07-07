package com.buddystudy.backend.profile.application.model

import com.buddystudy.account.domain.entity.UserEntity

fun UserEntity.toProfile() = UserProfileResponse(
    id = id,
    displayName = displayName,
    status = status,
    provider = provider,
    email = email,
    bio = bio,
    avatarUrl = avatarUrl,
    avatarSymbolName = avatarSymbolName,
    avatarColorSeed = avatarColorSeed,
    pageAccess = CommunityPageAccess(publicQuestions = allowPublicQuestions),
)
