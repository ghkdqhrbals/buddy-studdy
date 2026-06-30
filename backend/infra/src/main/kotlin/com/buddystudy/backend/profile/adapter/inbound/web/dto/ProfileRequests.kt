package com.buddystudy.backend.profile.adapter.inbound.web.dto

import com.buddystudy.backend.profile.application.model.CommunityPageAccess

data class ProfileUpdateRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
    val pageAccess: CommunityPageAccess? = null,
)
