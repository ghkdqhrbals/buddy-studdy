package com.buddystudy.avatar.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "avatar_categories")
class AvatarCategoryEntity(
    @Id
    @Column(name = "category_key", nullable = false, length = 64)
    var key: String = "",
    @Column(name = "title_ko", nullable = false, length = 120)
    var titleKo: String = "",
    @Column(name = "title_en", nullable = false, length = 120)
    var titleEn: String = "",
    @Column(nullable = false, length = 64)
    var slot: String = "",
    @Column(nullable = false)
    var required: Boolean = false,
    @Column(name = "single_select", nullable = false)
    var singleSelect: Boolean = true,
    @Column(name = "z_index", nullable = false)
    var zIndex: Int = 0,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
