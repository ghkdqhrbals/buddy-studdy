package com.buddystudy.backend.profile.application.model

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.fasterxml.jackson.module.kotlin.readValue

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
    allowPublicQuestions = allowPublicQuestions,
)

fun String?.toAvatarConfigMap(): Map<String, String>? {
    val source = this?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return runCatching {
        JsonMapperProvider.mapper.readValue<Map<String, String>>(source)
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
    }.getOrNull()
}

fun Map<String, String>.toAvatarConfigJson(): String =
    JsonMapperProvider.mapper.writeValueAsString(
        mapValues { (_, value) -> value.trim() }
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
            .toSortedMap()
    )

fun String?.toCompatibleBases(): List<String> {
    val source = this?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
    return runCatching { JsonMapperProvider.mapper.readValue<List<String>>(source) }.getOrDefault(emptyList())
}
