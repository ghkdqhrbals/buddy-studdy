package com.buddystudy.backend.profile.application.port.inbound

import com.buddystudy.backend.profile.application.model.CommunityPageAccess

data class ProfileUpdateCommand(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
    val pageAccess: CommunityPageAccess? = null,
)
