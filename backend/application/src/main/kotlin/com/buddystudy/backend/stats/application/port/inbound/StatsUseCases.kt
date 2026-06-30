package com.buddystudy.backend.stats.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.stats.application.model.StatsActivityResponse
import com.buddystudy.backend.stats.application.model.StatsQuery
import com.buddystudy.backend.stats.application.model.StatsResponse
import java.time.Instant

interface GetStudyStatsUseCase {
    fun stats(principal: Principal, limit: Int, offset: Int, query: StatsQuery = StatsQuery()): StatsResponse
    fun activity(principal: Principal, startAt: Instant? = null, endAt: Instant? = null): StatsActivityResponse
}

interface RefreshUserStatsUseCase {
    fun refreshAll(now: Instant = Instant.now())
}
