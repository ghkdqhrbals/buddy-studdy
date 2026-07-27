package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component

interface UserDeviceRepository : CoroutineCrudRepository<UserDeviceEntity, Long> {
    suspend fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    suspend fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?

    @Query(
        """
        select * from user_devices
        where user_id = :userId
          and logged_out_at is null
          and revoked_at is null
          and (session_expires_at is null or session_expires_at > current_timestamp)
        order by last_seen_at desc
        """
    )
    suspend fun findActiveByUserId(userId: Long): List<UserDeviceEntity>

    @Query(
        """
        select count(*)
        from user_devices
        where user_id = :userId
          and device_id = :deviceId
          and logged_out_at is null
          and revoked_at is null
          and (session_expires_at is null or session_expires_at > current_timestamp)
        """
    )
    suspend fun countActiveSessions(userId: Long, deviceId: String): Long
}

@Component
class UserDevicePersistenceAdapter(
    private val repository: UserDeviceRepository,
) : UserDevicePort {
    override suspend fun save(entity: UserDeviceEntity) = repository.save(entity)
    override suspend fun findByUserIdAndDeviceId(userId: Long, deviceId: String) = repository.findByUserIdAndDeviceId(userId, deviceId)
    override suspend fun findByIdAndUserId(id: Long, userId: Long) = repository.findByIdAndUserId(id, userId)
    override suspend fun findActiveByUserId(userId: Long) = repository.findActiveByUserId(userId)
    override suspend fun hasActiveSession(userId: Long, deviceId: String) =
        repository.countActiveSessions(userId, deviceId) > 0
}
