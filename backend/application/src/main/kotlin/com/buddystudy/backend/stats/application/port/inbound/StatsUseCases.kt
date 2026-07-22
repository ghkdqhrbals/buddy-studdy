package com.buddystudy.backend.stats.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.stats.application.model.StatsActivityResponse
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.model.StatsResponse
import java.time.Instant

interface GetStudyStatsUseCase {
    suspend fun stats(principal: Principal, limit: Int, offset: Int, query: StatsQuery = StatsQuery()): StatsResponse
    suspend fun activity(principal: Principal, startAt: Instant? = null, endAt: Instant? = null): StatsActivityResponse
}

interface RefreshUserStatsUseCase {
    suspend fun refreshAll(now: Instant = Instant.now())
}
