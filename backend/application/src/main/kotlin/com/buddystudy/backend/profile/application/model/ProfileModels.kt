package com.buddystudy.backend.profile.application.model

data class CommunityPageAccess(
    val publicQuestions: Boolean = true,
    val statistics: Boolean = false,
    val studyDetail: Boolean = false,
    val records: Boolean = false,
)

data class UserProfileResponse(
    val id: Long,
    val displayName: String,
    val status: String = "ANONYMOUS",
    val provider: String = "ANONYMOUS",
    val email: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val avatarSymbolName: String = "pixel-buddy",
    val avatarColorSeed: String = "avatar-color-mint",
    val pageAccess: CommunityPageAccess = CommunityPageAccess(),
)
