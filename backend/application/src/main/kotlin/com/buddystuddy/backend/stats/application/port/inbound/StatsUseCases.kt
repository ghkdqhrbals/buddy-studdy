package com.buddystuddy.backend.stats.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.stats.application.model.StatsActivityResponse
import com.buddystuddy.backend.stats.application.model.StatsQuery
import com.buddystuddy.backend.stats.application.model.StatsResponse
import java.time.Instant

interface GetStudyStatsUseCase {
    fun stats(principal: Principal, limit: Int, offset: Int, query: StatsQuery = StatsQuery()): StatsResponse
    fun activity(principal: Principal, startAt: Instant? = null, endAt: Instant? = null): StatsActivityResponse
}

interface RefreshUserStatsUseCase {
    fun refreshAll(now: Instant = Instant.now())
}
