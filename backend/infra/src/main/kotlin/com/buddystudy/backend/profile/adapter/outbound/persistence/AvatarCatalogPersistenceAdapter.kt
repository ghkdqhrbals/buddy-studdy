package com.buddystudy.backend.profile.adapter.outbound.persistence

import com.buddystudy.avatar.domain.entity.AvatarCategoryEntity
import com.buddystudy.avatar.domain.entity.AvatarItemEntity
import com.buddystudy.avatar.domain.entity.UserAvatarItemEntity
import com.buddystudy.backend.profile.application.port.outbound.AvatarCatalogPort
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface AvatarCategoryRepository : CoroutineCrudRepository<AvatarCategoryEntity, String> {
    suspend fun findByActiveTrueOrderBySortOrderAscKeyAsc(): List<AvatarCategoryEntity>
}

interface AvatarItemRepository : CoroutineCrudRepository<AvatarItemEntity, String> {
    suspend fun findByActiveTrueOrderBySortOrderAscKeyAsc(): List<AvatarItemEntity>
    suspend fun findByKeyInAndActiveTrue(keys: Collection<String>): List<AvatarItemEntity>
}

interface UserAvatarItemRepository : CoroutineCrudRepository<UserAvatarItemEntity, Long> {
    suspend fun findByUserId(userId: Long): List<UserAvatarItemEntity>
}

@Component
class AvatarCatalogPersistenceAdapter(
    private val categories: AvatarCategoryRepository,
    private val items: AvatarItemRepository,
    private val userItems: UserAvatarItemRepository,
) : AvatarCatalogPort {
    override suspend fun activeCategories(): List<AvatarCategoryEntity> =
        categories.findByActiveTrueOrderBySortOrderAscKeyAsc()

    override suspend fun availableItems(userId: Long): List<AvatarItemEntity> {
        val grantedKeys = userItems.findByUserId(userId).map { it.itemKey }.toSet()
        return items.findByActiveTrueOrderBySortOrderAscKeyAsc()
            .filter { item ->
                item.defaultGrant || item.key in grantedKeys
            }
    }

    override suspend fun activeItemsByKeys(keys: Collection<String>): List<AvatarItemEntity> =
        if (keys.isEmpty()) emptyList() else items.findByKeyInAndActiveTrue(keys)
}
