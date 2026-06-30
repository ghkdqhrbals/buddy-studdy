package com.buddystudy.backend.auth.adapter.outbound.persistence

import com.buddystudy.backend.auth.application.port.outbound.UserDevicePort
import com.buddystudy.auth.domain.entity.UserDeviceEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserDeviceRepository : JpaRepository<UserDeviceEntity, Long>, UserDevicePort {
    override fun findByUserIdAndDeviceId(userId: Long, deviceId: String): UserDeviceEntity?
    override fun findByIdAndUserId(id: Long, userId: Long): UserDeviceEntity?

    @Query(
        """
        select ud
        from UserDeviceEntity ud
        where ud.userId = :userId
          and ud.loggedOutAt is null
          and ud.revokedAt is null
          and (ud.sessionExpiresAt is null or ud.sessionExpiresAt > current_timestamp)
        order by ud.lastSeenAt desc
        """
    )
    override fun findActiveByUserId(userId: Long): List<UserDeviceEntity>

    @Query(
        """
        select case when count(ud) > 0 then true else false end
        from UserDeviceEntity ud
        where ud.userId = :userId
          and ud.deviceId = :deviceId
          and ud.loggedOutAt is null
          and ud.revokedAt is null
          and (ud.sessionExpiresAt is null or ud.sessionExpiresAt > current_timestamp)
        """
    )
    override fun hasActiveSession(userId: Long, deviceId: String): Boolean
}
