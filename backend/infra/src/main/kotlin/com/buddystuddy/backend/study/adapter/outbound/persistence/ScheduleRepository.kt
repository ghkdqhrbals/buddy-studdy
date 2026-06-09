package com.buddystuddy.backend.study.adapter.outbound.persistence

import com.buddystuddy.study.domain.entity.ScheduleEntity
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ScheduleRepository : JpaRepository<ScheduleEntity, Long>, SchedulePort {
    override fun findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(deviceId: String, userId: Long?): ScheduleEntity?
    override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long?): ScheduleEntity?
    override fun findByIdAndUserId(id: Long, userId: Long?): ScheduleEntity?
    override fun findByDeviceIdAndUserIdAndTopic(deviceId: String, userId: Long?, topic: String): ScheduleEntity?
    override fun findByUserIdAndTopic(userId: Long?, topic: String): ScheduleEntity?

    @Query("select s from ScheduleEntity s where s.enabled = true and s.nextDueAt is not null and s.nextDueAt <= :now order by s.nextDueAt asc")
    override fun findDue(@Param("now") now: Instant, pageable: Pageable): List<ScheduleEntity>
}
