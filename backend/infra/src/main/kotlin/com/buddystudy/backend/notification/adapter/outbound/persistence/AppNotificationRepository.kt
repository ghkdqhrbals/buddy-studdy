package com.buddystudy.backend.notification.adapter.outbound.persistence

import com.buddystudy.backend.config.saveEntity
import com.buddystudy.backend.config.selectPage
import com.buddystudy.backend.notification.application.port.outbound.NotificationPersistencePort
import com.buddystudy.notification.domain.entity.AppNotificationEntity
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class AppNotificationRepository(
    private val template: R2dbcEntityTemplate,
) : NotificationPersistencePort {
    override suspend fun save(entity: AppNotificationEntity): AppNotificationEntity = template.saveEntity(entity, entity.id)

    override suspend fun findByEventId(eventId: String): AppNotificationEntity? = one(Criteria.where("event_id").`is`(eventId))

    override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): AppNotificationEntity? =
        one(Criteria.where("id").`is`(id).and("user_id").`is`(userId).and("deleted_at").isNull)

    override suspend fun findByIdAndDeviceIdAndUserIdIsNullAndDeletedAtIsNull(
        id: Long,
        deviceId: String,
    ): AppNotificationEntity? = one(
        Criteria.where("id").`is`(id).and("device_id").`is`(deviceId)
            .and("user_id").isNull.and("deleted_at").isNull,
    )

    override suspend fun findByUserIdAndDeletedAtIsNull(
        userId: Long,
        pageable: Pageable,
    ): Page<AppNotificationEntity> = page(
        Criteria.where("user_id").`is`(userId).and("deleted_at").isNull,
        pageable,
    )

    override suspend fun findVisible(
        userId: Long?,
        deviceId: String,
        pageable: Pageable,
    ): Page<AppNotificationEntity> = page(visibleCriteria(userId, deviceId), pageable)

    override suspend fun countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId: Long): Long = count(
        Criteria.where("user_id").`is`(userId).and("read_at").isNull.and("deleted_at").isNull,
    )

    override suspend fun countVisibleUnread(userId: Long?, deviceId: String): Long =
        count(visibleCriteria(userId, deviceId).and("read_at").isNull)

    override suspend fun markAllDeleted(userId: Long, deletedAt: Instant): Int = update(
        Criteria.where("user_id").`is`(userId).and("deleted_at").isNull,
        Update.update("deleted_at", deletedAt).set("updated_at", deletedAt),
    )

    override suspend fun markVisibleRead(userId: Long?, deviceId: String, readAt: Instant): Int = update(
        visibleCriteria(userId, deviceId).and("read_at").isNull,
        Update.update("read_at", readAt).set("updated_at", readAt),
    )

    override suspend fun markVisibleDeleted(userId: Long?, deviceId: String, deletedAt: Instant): Int = update(
        visibleCriteria(userId, deviceId),
        Update.update("deleted_at", deletedAt).set("updated_at", deletedAt),
    )

    override suspend fun markUserThreadRead(
        userId: Long,
        threadType: String,
        threadId: String,
        readAt: Instant,
    ): Int = update(
        Criteria.where("user_id").`is`(userId).and("thread_type").`is`(threadType)
            .and("thread_id").`is`(threadId).and("read_at").isNull.and("deleted_at").isNull,
        Update.update("read_at", readAt).set("updated_at", readAt),
    )

    override suspend fun claimPush(id: Long, now: Instant, staleBefore: Instant): Int {
        val stale = Criteria.where("push_claimed_at").isNull.or("push_claimed_at").lessThan(staleBefore)
        return update(
            Criteria.where("id").`is`(id).and("should_push").`is`(true)
                .and("push_sent_at").isNull.and(stale),
            Update.update("push_claimed_at", now).set("updated_at", now),
        )
    }

    override suspend fun markPushSent(id: Long, now: Instant): Int = update(
        Criteria.where("id").`is`(id),
        Update.update("push_sent_at", now).set("updated_at", now).set("push_error", null),
    )

    override suspend fun markPushFailed(id: Long, error: String, now: Instant): Int = update(
        Criteria.where("id").`is`(id),
        Update.update("push_error", error).set("updated_at", now),
    )

    private suspend fun one(criteria: Criteria): AppNotificationEntity? =
        template.selectOne(Query.query(criteria), AppNotificationEntity::class.java).awaitSingleOrNull()

    private suspend fun page(criteria: Criteria, pageable: Pageable): Page<AppNotificationEntity> {
        val query = Query.query(criteria).sort(Sort.by(Sort.Direction.DESC, "created_at", "id"))
        return template.selectPage(query, Query.query(criteria), AppNotificationEntity::class.java, pageable)
    }

    private fun visibleCriteria(userId: Long?, deviceId: String): Criteria {
        val device = Criteria.where("user_id").isNull.and("device_id").`is`(deviceId)
        val audience = if (userId == null) device else Criteria.where("user_id").`is`(userId).or(device)
        return Criteria.where("deleted_at").isNull.and(audience)
    }

    private suspend fun count(criteria: Criteria): Long =
        template.count(Query.query(criteria), AppNotificationEntity::class.java).awaitSingle()

    private suspend fun update(criteria: Criteria, update: Update): Int =
        template.update(AppNotificationEntity::class.java).matching(Query.query(criteria)).apply(update).awaitSingle().toInt()
}
