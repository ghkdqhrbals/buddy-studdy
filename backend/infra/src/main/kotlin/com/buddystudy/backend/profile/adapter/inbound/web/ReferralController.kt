package com.buddystudy.backend.profile.adapter.inbound.web

import com.buddystudy.backend.common.adapter.inbound.web.principalOrThrow
import com.buddystudy.backend.profile.application.model.ReferralSummaryResponse
import com.buddystudy.backend.profile.application.port.inbound.ReferralUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RedeemReferralRequest(
    @field:NotBlank
    @field:Pattern(regexp = "BS-[A-Za-z2-9]{8}")
    var code: String = "",
)

@RestController
@RequestMapping("/api/v1/referrals")
@Tag(name = "Referrals", description = "Tier 2 referral rewards for registered users.")
class ReferralController(
    private val referrals: ReferralWebPort,
) {
    @GetMapping("/me")
    @Operation(summary = "Fetch my referral code and earned Tier 2 reward months")
    suspend fun summary(authentication: Authentication): ReferralSummaryResponse = referrals.summary(authentication)

    @PostMapping("/redeem")
    @Operation(summary = "Redeem another user's referral code once")
    suspend fun redeem(
        @Valid @RequestBody request: RedeemReferralRequest,
        authentication: Authentication,
    ): ReferralSummaryResponse = referrals.redeem(request, authentication)
}

interface ReferralWebPort {
    suspend fun summary(authentication: Authentication): ReferralSummaryResponse
    suspend fun redeem(request: RedeemReferralRequest, authentication: Authentication): ReferralSummaryResponse
}

@Component
class ReferralWebAdapter(
    private val referrals: ReferralUseCase,
) : ReferralWebPort {
    override suspend fun summary(authentication: Authentication): ReferralSummaryResponse =
        referrals.summary(authentication.principalOrThrow())

    override suspend fun redeem(
        request: RedeemReferralRequest,
        authentication: Authentication,
    ): ReferralSummaryResponse = referrals.redeem(authentication.principalOrThrow(), request.code)
}
