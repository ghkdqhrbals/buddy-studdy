package com.buddystudy.backend.profile.adapter.inbound.web.dto

data class ProfileUpdateRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
    val avatarMode: String? = null,
    val avatarConfig: Map<String, String>? = null,
    val avatarImageBase64: String? = null,
    val avatarImageContentType: String? = null,
)

data class AvatarUpdateRequest(
    val avatarMode: String = "BUILDER",
    val avatarConfig: Map<String, String> = emptyMap(),
    val avatarColorSeed: String? = null,
)
