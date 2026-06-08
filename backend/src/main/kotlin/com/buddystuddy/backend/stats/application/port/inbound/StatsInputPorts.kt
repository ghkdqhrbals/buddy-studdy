package com.buddystuddy.backend.stats.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.stats.application.model.StatsResponse

interface GetStudyStatsInputPort {
    fun stats(principal: Principal, limit: Int, offset: Int): StatsResponse
}
