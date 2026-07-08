package com.buddystudy.backend.profile.application.model

import com.buddystudy.account.domain.entity.UserEntity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

internal val profileAvatarMapper = jacksonObjectMapper()

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
    avatarMode = avatarMode,
    avatarConfig = avatarConfig.toAvatarConfigMap(),
)

fun String?.toAvatarConfigMap(): Map<String, String>? {
    val source = this?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return runCatching {
        profileAvatarMapper.readValue<Map<String, String>>(source)
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
    }.getOrNull()
}

fun Map<String, String>.toAvatarConfigJson(): String =
    profileAvatarMapper.writeValueAsString(
        mapValues { (_, value) -> value.trim() }
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
            .toSortedMap()
    )

fun String?.toCompatibleBases(): List<String> {
    val source = this?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
    return runCatching { profileAvatarMapper.readValue<List<String>>(source) }.getOrDefault(emptyList())
}
