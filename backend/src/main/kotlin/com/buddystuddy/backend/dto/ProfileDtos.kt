package com.buddystuddy.backend.dto

data class ProfileUpdateRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
    val pageAccess: CommunityPageAccess? = null,
)

data class CommunityPageAccess(
    val publicQuestions: Boolean = true,
    val statistics: Boolean = false,
    val studyDetail: Boolean = false,
    val records: Boolean = false,
)

data class UserProfileResponse(
    val id: Long,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val avatarSymbolName: String = "pixel-buddy",
    val avatarColorSeed: String = "avatar-color-mint",
    val pageAccess: CommunityPageAccess = CommunityPageAccess(),
)
