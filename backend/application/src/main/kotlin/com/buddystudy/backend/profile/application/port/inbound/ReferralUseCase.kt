package com.buddystudy.backend.profile.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.profile.application.model.ReferralLandingResponse
import com.buddystudy.backend.profile.application.model.ReferralSummaryResponse

interface ReferralUseCase {
    suspend fun summary(principal: Principal): ReferralSummaryResponse
    suspend fun redeem(principal: Principal, code: String): ReferralSummaryResponse
    suspend fun landing(code: String): ReferralLandingResponse
}
