package com.buddystudy.backend.profile.adapter.outbound.persistence

import com.buddystudy.avatar.domain.entity.AvatarCategoryEntity
import com.buddystudy.avatar.domain.entity.AvatarItemEntity
import com.buddystudy.avatar.domain.entity.UserAvatarItemEntity
import com.buddystudy.backend.profile.application.port.outbound.AvatarCatalogPort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component

interface AvatarCategoryRepository : JpaRepository<AvatarCategoryEntity, String> {
    fun findByActiveTrueOrderBySortOrderAscKeyAsc(): List<AvatarCategoryEntity>
}

interface AvatarItemRepository : JpaRepository<AvatarItemEntity, String> {
    fun findByActiveTrueOrderBySortOrderAscKeyAsc(): List<AvatarItemEntity>
    fun findByKeyInAndActiveTrue(keys: Collection<String>): List<AvatarItemEntity>
}

interface UserAvatarItemRepository : JpaRepository<UserAvatarItemEntity, Long> {
    fun findByUserId(userId: Long): List<UserAvatarItemEntity>
}

@Component
class AvatarCatalogPersistenceAdapter(
    private val categories: AvatarCategoryRepository,
    private val items: AvatarItemRepository,
    private val userItems: UserAvatarItemRepository,
) : AvatarCatalogPort {
    override fun activeCategories(): List<AvatarCategoryEntity> =
        categories.findByActiveTrueOrderBySortOrderAscKeyAsc()

    override fun availableItems(userId: Long): List<AvatarItemEntity> {
        val grantedKeys = userItems.findByUserId(userId).map { it.itemKey }.toSet()
        return items.findByActiveTrueOrderBySortOrderAscKeyAsc()
            .filter { item ->
                item.defaultGrant || item.key in grantedKeys
            }
    }

    override fun activeItemsByKeys(keys: Collection<String>): List<AvatarItemEntity> =
        if (keys.isEmpty()) emptyList() else items.findByKeyInAndActiveTrue(keys)
}
