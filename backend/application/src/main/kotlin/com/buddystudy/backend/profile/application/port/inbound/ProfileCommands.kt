package com.buddystudy.backend.profile.application.port.inbound

data class ProfileUpdateCommand(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
    val avatarMode: String? = null,
    val avatarConfig: Map<String, String>? = null,
)

data class AvatarUpdateCommand(
    val avatarMode: String = "BUILDER",
    val avatarConfig: Map<String, String> = emptyMap(),
    val avatarColorSeed: String? = null,
)
