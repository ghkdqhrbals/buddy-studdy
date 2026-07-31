package com.buddystudy.backend.profile.application.model

import com.buddystudy.avatar.domain.entity.AvatarCategoryEntity
import com.buddystudy.avatar.domain.entity.AvatarItemEntity

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
    val avatarMode: String = "BUILDER",
    val avatarConfig: Map<String, String>? = null,
    val allowPublicQuestions: Boolean = true,
)

data class AvatarCategoryResponse(
    val key: String,
    val titleKo: String,
    val titleEn: String,
    val slot: String,
    val required: Boolean,
    val singleSelect: Boolean,
    val zIndex: Int,
    val sortOrder: Int,
)

data class AvatarItemResponse(
    val key: String,
    val category: String,
    val slot: String,
    val displayNameKo: String,
    val displayNameEn: String,
    val assetName: String,
    val colorHex: String,
    val defaultGrant: Boolean,
    val compatibleBases: List<String>,
    val zIndex: Int,
    val sortOrder: Int,
)

data class AvatarCatalogResponse(
    val categories: List<AvatarCategoryResponse>,
    val items: List<AvatarItemResponse>,
    val defaultConfig: Map<String, String>,
    val currentConfig: Map<String, String>,
)

fun AvatarCategoryEntity.toResponse() = AvatarCategoryResponse(
    key = key,
    titleKo = titleKo,
    titleEn = titleEn,
    slot = slot.databaseValue,
    required = required,
    singleSelect = singleSelect,
    zIndex = zIndex,
    sortOrder = sortOrder,
)

fun AvatarItemEntity.toResponse(compatibleBases: List<String>) = AvatarItemResponse(
    key = key,
    category = category,
    slot = slot.databaseValue,
    displayNameKo = displayNameKo,
    displayNameEn = displayNameEn,
    assetName = assetName,
    colorHex = colorHex,
    defaultGrant = defaultGrant,
    compatibleBases = compatibleBases,
    zIndex = zIndex,
    sortOrder = sortOrder,
)
