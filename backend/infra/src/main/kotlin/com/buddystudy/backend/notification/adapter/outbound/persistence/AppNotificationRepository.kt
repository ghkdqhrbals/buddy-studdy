package com.buddystudy.backend.notification.adapter.outbound.persistence

import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.notification.domain.entity.AppNotificationEntity
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
    override fun findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(id: Long, deviceId: String): AppNotificationEntity?
    override fun findByUserIdAndDeletedAtIsNull(userId: Long, pageable: Pageable): Page<AppNotificationEntity>
    override fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long

    @Query(
        value = """
        select n
          from AppNotificationEntity n
         where n.deletedAt is null
           and (
                (:userId is not null and n.userId = :userId)
                or (n.userId is null and n.deviceId = :deviceId)
           )
         order by case when n.userId is not null then 0 else 1 end,
                  n.createdAt desc,
                  n.id desc
        """,
        countQuery = """
        select count(n)
          from AppNotificationEntity n
         where n.deletedAt is null
           and (
                (:userId is not null and n.userId = :userId)
                or (n.userId is null and n.deviceId = :deviceId)
           )
        """,
    )
    override fun findVisible(
        @Param("userId") userId: Long?,
        @Param("deviceId") deviceId: String,
        pageable: Pageable,
    ): Page<AppNotificationEntity>

    @Query(
        """
        select count(n)
          from AppNotificationEntity n
         where n.readAt is null
           and n.deletedAt is null
           and (
                (:userId is not null and n.userId = :userId)
                or (n.userId is null and n.deviceId = :deviceId)
           )
        """
    )
    override fun countVisibleUnread(@Param("userId") userId: Long?, @Param("deviceId") deviceId: String): Long

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
           set n.deletedAt = :deletedAt,
               n.updatedAt = :deletedAt
         where n.deletedAt is null
           and (
                (:userId is not null and n.userId = :userId)
                or (n.userId is null and n.deviceId = :deviceId)
           )
        """
    )
    override fun markVisibleDeleted(
        @Param("userId") userId: Long?,
        @Param("deviceId") deviceId: String,
        @Param("deletedAt") deletedAt: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        update AppNotificationEntity n
           set n.readAt = :readAt,
               n.updatedAt = :readAt
         where n.userId = :userId
           and n.threadType = :threadType
           and n.threadId = :threadId
           and n.readAt is null
           and n.deletedAt is null
        """
    )
    override fun markUserThreadRead(
        @Param("userId") userId: Long,
        @Param("threadType") threadType: String,
        @Param("threadId") threadId: String,
        @Param("readAt") readAt: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        update AppNotificationEntity n
           set n.pushClaimedAt = :now,
               n.updatedAt = :now
         where n.id = :id
           and n.shouldPush = true
           and n.pushSentAt is null
           and (n.pushClaimedAt is null or n.pushClaimedAt < :staleBefore)
        """
    )
    override fun claimPush(
        @Param("id") id: Long,
        @Param("now") now: Instant,
        @Param("staleBefore") staleBefore: Instant,
    ): Int

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
