package com.buddystudy.backend.profile.adapter.inbound.web.dto

data class ProfileUpdateRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarSymbolName: String? = null,
    val avatarColorSeed: String? = null,
)
