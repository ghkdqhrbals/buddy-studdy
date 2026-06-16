package com.buddystuddy.backend.notification.adapter.outbound.persistence

import com.buddystuddy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystuddy.notification.domain.entity.AppNotificationEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface AppNotificationRepository : JpaRepository<AppNotificationEntity, Long>, NotificationPersistencePort {
    override fun findByEventId(eventId: String): AppNotificationEntity?
    override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity?
    override fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity>
    override fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long

    @Modifying
    @Transactional
    @Query(
        """
        update AppNotificationEntity n
           set n.deletedAt = :deletedAt,
               n.updatedAt = :deletedAt
         where n.userId = :userId
           and n.deletedAt is null
        """
    )
    override fun markAllDeleted(@Param("userId") userId: Long, @Param("deletedAt") deletedAt: Instant): Int

    @Modifying
    @Transactional
    @Query(
        """
        update AppNotificationEntity n
           set n.pushClaimedAt = :now,
               n.updatedAt = :now
         where n.id = :id
           and n.shouldPush = true
           and n.pushClaimedAt is null
           and n.pushSentAt is null
        """
    )
    override fun claimPush(@Param("id") id: Long, @Param("now") now: Instant): Int

    @Modifying
    @Transactional
    @Query(
        """
        update AppNotificationEntity n
           set n.pushSentAt = :now,
               n.updatedAt = :now,
               n.pushError = null
         where n.id = :id
        """
    )
    override fun markPushSent(@Param("id") id: Long, @Param("now") now: Instant): Int

    @Modifying
    @Transactional
    @Query(
        """
        update AppNotificationEntity n
           set n.pushError = :error,
               n.updatedAt = :now
         where n.id = :id
        """
    )
    override fun markPushFailed(@Param("id") id: Long, @Param("error") error: String, @Param("now") now: Instant): Int
}
