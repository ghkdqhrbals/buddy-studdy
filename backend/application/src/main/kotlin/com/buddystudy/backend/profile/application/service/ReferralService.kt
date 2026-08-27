package com.buddystudy.backend.profile.application.service

import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.profile.application.model.ReferralSummaryResponse
import com.buddystudy.backend.profile.application.port.inbound.ReferralUseCase
import com.buddystudy.backend.profile.application.port.outbound.ReferralPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneOffset

@Service
class ReferralService(
    private val users: UserPort,
    private val referrals: ReferralPort,
    private val codeGenerator: ReferralCodeGenerator,
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
        val normalizedCode = code.trim().uppercase()
        if (!CODE.matches(normalizedCode)) throw validation("Referral code is invalid.")
        val inviterUserId = referrals.inviterUserId(normalizedCode)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        if (inviterUserId == principal.userId) throw validation("You cannot redeem your own referral code.")

        val inviter = users.findById(inviterUserId)
        if (inviter?.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        }

        val now = clock.instant()
        if (referrals.lockUsers(listOf(inviterUserId, principal.userId).sorted()) != 2) {
            throw validation("Referral accounts are unavailable.")
        }
        if (referrals.hasRedeemed(principal.userId)) throw validation("A referral code has already been redeemed.")
        val referralId = referrals.createReferral(inviterUserId, principal.userId, normalizedCode, now)
            ?: throw validation("A referral code has already been redeemed.")

        listOf(inviterUserId, principal.userId).forEach { beneficiaryUserId ->
            val startsAt = referrals.rewardBase(beneficiaryUserId, now)
            val endsAt = startsAt.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()
            check(
                referrals.grantTier2Month(
                    referralId = referralId,
                    beneficiaryUserId = beneficiaryUserId,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    now = now,
                ),
            ) { "Referral reward grant was not persisted." }
        }
        return response(principal.userId, findOrCreateCode(principal.userId))
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
            referrals.codeForUser(userId)?.let { return it }
        }
        throw IllegalStateException("A unique referral code could not be allocated.")
    }

    private suspend fun response(userId: Long, code: String): ReferralSummaryResponse {
        val summary = referrals.accountSummary(userId)
        return ReferralSummaryResponse(
            code = code,
            successfulReferralCount = summary.successfulReferralCount,
            rewardMonthsEarned = summary.rewardMonthsEarned,
            rewardStartsAt = summary.rewardStartsAt,
            rewardEndsAt = summary.rewardEndsAt,
            hasRedeemedReferral = summary.hasRedeemedReferral,
        )
    }

    private fun validation(message: String) = ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ApiErrorCode.VALIDATION_ERROR,
        message,
    )

    private companion object {
        val CODE = Regex("^BS-[A-Z2-9]{8}$")
        const val MAX_CODE_ATTEMPTS = 8
    }
}
