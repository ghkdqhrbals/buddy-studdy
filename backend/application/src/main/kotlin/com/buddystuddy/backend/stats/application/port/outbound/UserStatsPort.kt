package com.buddystuddy.backend.stats.application.port.outbound

import com.buddystuddy.stats.domain.entity.UserStatsEntity
import java.time.LocalDate

interface UserStatsPort {
    fun replaceAll(rows: Collection<UserStatsEntity>)
    fun syncAll(rows: Collection<UserStatsEntity>)
    fun findByUser(userId: Long, startDate: LocalDate?, endDate: LocalDate?, query: String?): List<UserStatsEntity>
}
