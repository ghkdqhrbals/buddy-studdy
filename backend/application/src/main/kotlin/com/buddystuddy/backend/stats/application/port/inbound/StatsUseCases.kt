package com.buddystuddy.backend.stats.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.stats.application.model.StatsResponse

interface GetStudyStatsUseCase {
    fun stats(principal: Principal, limit: Int, offset: Int): StatsResponse
}
