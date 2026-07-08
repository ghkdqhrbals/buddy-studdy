package com.buddystudy.avatar.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "user_avatar_items",
    uniqueConstraints = [UniqueConstraint(name = "uq_user_avatar_items_user_item", columnNames = ["user_id", "item_key"])],
    indexes = [Index(name = "idx_user_avatar_items_user", columnList = "user_id")],
)
class UserAvatarItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,
    @Column(name = "item_key", nullable = false, length = 96)
    var itemKey: String = "",
    @Column(name = "granted_source", nullable = false, length = 64)
    var grantedSource: String = "SYSTEM",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
