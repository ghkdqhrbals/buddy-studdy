package com.buddystudy.backend.profile.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class ProfileUpdateRequest @JsonCreator constructor(
    @JsonProperty("displayName") val displayName: String? = null,
    @JsonProperty("bio") val bio: String? = null,
    @JsonProperty("avatarSymbolName") val avatarSymbolName: String? = null,
    @JsonProperty("avatarColorSeed") val avatarColorSeed: String? = null,
    @JsonProperty("avatarMode") val avatarMode: String? = null,
    @JsonProperty("avatarConfig") val avatarConfig: Map<String, String>? = null,
    @JsonProperty("allowPublicQuestions") val allowPublicQuestions: Boolean? = null,
)

data class AvatarUpdateRequest @JsonCreator constructor(
    @JsonProperty("avatarMode") val avatarMode: String = "BUILDER",
    @JsonProperty("avatarConfig") val avatarConfig: Map<String, String> = emptyMap(),
    @JsonProperty("avatarColorSeed") val avatarColorSeed: String? = null,
)
