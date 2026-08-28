package com.buddystudy.backend.profile.application.service

import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.profile.application.model.ReferralLandingResponse
import com.buddystudy.backend.profile.application.model.ReferralSummaryResponse
import com.buddystudy.backend.profile.application.port.inbound.ReferralUseCase
import com.buddystudy.backend.profile.application.port.outbound.ReferralPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ReferralService(
    private val users: UserPort,
    private val referrals: ReferralPort,
    private val codeGenerator: ReferralCodeGenerator,
    private val rewards: ReferralRewardManager,
    private val links: ReferralLinkProvider,
    private val clock: Clock = Clock.systemUTC(),
) : ReferralUseCase {

    @Transactional
    override suspend fun summary(principal: Principal): ReferralSummaryResponse {
        requireActiveUser(principal)
        return response(principal.userId, findOrCreateCode(principal.userId))
    }

    @Transactional
    override suspend fun redeem(principal: Principal, code: String): ReferralSummaryResponse {
        requireActiveUser(principal)
        rewards.redeemDuringSignupGrace(principal.userId, code, clock.instant())
        return response(principal.userId, findOrCreateCode(principal.userId))
    }

    @Transactional(readOnly = true)
    override suspend fun landing(code: String): ReferralLandingResponse {
        val normalizedCode = ReferralCodePolicy.normalizeOrNull(code)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        val inviterUserId = referrals.inviterUserId(normalizedCode)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        if (users.findById(inviterUserId)?.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        }
        return links.landing(normalizedCode)
    }

    private suspend fun requireActiveUser(principal: Principal) {
        if (principal.anonymous) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_ACCESS_TOKEN_REQUIRED, "Referral rewards require an active login.")
        }
        if (users.findById(principal.userId)?.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.USER_INACTIVE, "Referral rewards require an active account.")
        }
    }

    private suspend fun findOrCreateCode(userId: Long): String {
        referrals.codeForUser(userId)?.let { return it }
        repeat(MAX_CODE_ATTEMPTS) {
            val code = codeGenerator.next()
            if (referrals.createCode(userId, code, clock.instant())) return code
            referrals.lockCodeForUser(userId)?.let { return it }
        }
        throw IllegalStateException("A unique referral code could not be allocated.")
    }

    private suspend fun response(userId: Long, code: String): ReferralSummaryResponse {
        val summary = referrals.accountSummary(userId)
        return ReferralSummaryResponse(
            code = code,
            referralUrl = links.referralUrl(code),
            successfulReferralCount = summary.successfulReferralCount,
            rewardMonthsEarned = summary.rewardMonthsEarned,
            rewardStartsAt = summary.rewardStartsAt,
            rewardEndsAt = summary.rewardEndsAt,
            hasRedeemedReferral = summary.hasRedeemedReferral,
        )
    }

    private companion object {
        const val MAX_CODE_ATTEMPTS = 8
    }
}
