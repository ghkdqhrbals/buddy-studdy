package com.buddystudy.backend.profile.application.port.outbound

import com.buddystudy.avatar.domain.entity.AvatarCategoryEntity
import com.buddystudy.avatar.domain.entity.AvatarItemEntity

interface AvatarCatalogPort {
    fun activeCategories(): List<AvatarCategoryEntity>
    fun availableItems(userId: Long): List<AvatarItemEntity>
    fun activeItemsByKeys(keys: Collection<String>): List<AvatarItemEntity>
}
