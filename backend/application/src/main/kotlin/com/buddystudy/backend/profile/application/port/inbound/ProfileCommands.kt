package com.buddystudy.backend.profile.application.port.inbound

data class ProfileUpdateCommand(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
)
