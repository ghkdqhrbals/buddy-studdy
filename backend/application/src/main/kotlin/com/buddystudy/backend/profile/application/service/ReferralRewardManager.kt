package com.buddystudy.backend.profile.application.service

import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.model.PendingReferralAttribution
import com.buddystudy.backend.profile.application.model.PendingReferralAttributionStatus
import com.buddystudy.backend.profile.application.model.ReferralRecord
import com.buddystudy.backend.profile.application.port.outbound.ReferralPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@Component
class ReferralRewardManager(
    private val users: UserPort,
    private val referrals: ReferralPort,
    private val properties: BuddyStudyProperties,
) {
    suspend fun capturePendingAttribution(userId: Long, rawCode: String?, now: Instant): Boolean {
        val user = users.findById(userId) ?: return false
        if (user.status != UserStatus.PENDING_TERMS) return false
        referrals.pendingAttribution(userId)?.let { existing ->
            return existing.status == PendingReferralAttributionStatus.PENDING && existing.inviterUserId != null
        }
        if (rawCode.isNullOrBlank()) return false

        val code = ReferralCodePolicy.normalizeOrNull(rawCode) ?: return false

        val inviterUserId = referrals.inviterUserId(code) ?: return false
        if (inviterUserId == userId) return false
        if (users.findById(inviterUserId)?.status != UserStatus.ACTIVE) return false

        if (referrals.createPendingAttribution(inviterUserId, userId, code, now)) return true
        val concurrent = referrals.lockPendingAttribution(userId)
            ?: throw IllegalStateException("Pending referral attribution was not persisted.")
        check(concurrent.referredUserId == userId) { "Pending referral attribution belongs to another account." }
        return concurrent.status == PendingReferralAttributionStatus.PENDING && concurrent.inviterUserId != null
    }

    suspend fun activatePendingAttribution(userId: Long, now: Instant) {
        val attribution = referrals.lockPendingAttribution(userId) ?: return
        if (attribution.status != PendingReferralAttributionStatus.PENDING) return
        val inviterUserId = attribution.inviterUserId
        if (inviterUserId == null) {
            reject(attribution.id, now)
            return
        }

        if (referrals.lockUsers(listOf(inviterUserId, userId).sorted()) != 2) {
            reject(attribution.id, now)
            return
        }

        val inviter = users.findById(inviterUserId)
        val referred = users.findById(userId)
        val codeOwner = referrals.inviterUserId(attribution.referralCode)
        if (
            inviter?.status != UserStatus.ACTIVE ||
            referred?.status != UserStatus.ACTIVE ||
            codeOwner != inviterUserId ||
            inviterUserId == userId
        ) {
            reject(attribution.id, now)
            return
        }

        grantLocked(inviterUserId, userId, attribution.referralCode, now)
        check(
            referrals.completePendingAttribution(
                attributionId = attribution.id,
                expectedStatus = PendingReferralAttributionStatus.PENDING,
                status = PendingReferralAttributionStatus.REWARDED,
                now = now,
            ),
        ) { "Pending referral attribution was not completed." }
    }

    suspend fun redeemDuringSignupGrace(userId: Long, rawCode: String, now: Instant) {
        val code = ReferralCodePolicy.normalize(rawCode)
        val referred = users.findById(userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral account was not found.")
        if (referred.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.USER_INACTIVE, "Referral rewards require an active account.")
        }

        referrals.referralForReferredUser(userId)?.let { existing ->
            redeemExistingReferral(existing, userId, code, now)
            return
        }
        referrals.pendingAttribution(userId)?.let { existing ->
            resolveExistingAttribution(existing, userId, code, now)
            return
        }

        // V91 could cascade a referral before V93 introduced durable claims. A surviving
        // REFERRAL membership without its grant is therefore treated conservatively as a
        // prior redemption. This can reject a legacy inviter-only orphan, but prevents a
        // previously rewarded referred account from minting another month.
        if (referrals.hasUntrackedReferralMembership(userId)) {
            throw conflict("A referral reward has already been attributed to this account.")
        }

        val graceHours = properties.referral.manualRedemptionGraceHours.coerceAtLeast(0)
        val graceDeadline = referred.createdAt.plus(Duration.ofHours(graceHours))
        if (now.isAfter(graceDeadline)) {
            throw conflict("Referral recovery is only available during the first $graceHours hours after signup.")
        }

        val inviterUserId = activeInviter(code)
        if (inviterUserId == userId) throw validation("You cannot redeem your own referral code.")

        if (referrals.lockUsers(listOf(inviterUserId, userId).sorted()) != 2) {
            throw validation("Referral accounts are unavailable.")
        }

        referrals.lockReferralForReferredUser(userId)?.let { concurrent ->
            redeemLockedReferral(concurrent, userId, code, now)
            return
        }
        referrals.lockPendingAttribution(userId)?.let { concurrent ->
            resolveExistingAttribution(concurrent, userId, code, now)
            return
        }

        val inviter = users.findById(inviterUserId)
        val lockedReferred = users.findById(userId)
        if (inviter?.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        }
        if (lockedReferred?.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.USER_INACTIVE, "Referral rewards require an active account.")
        }

        grantLocked(inviterUserId, userId, code, now)
        ensureRewardedAttribution(inviterUserId, userId, code, now)
    }

    private suspend fun redeemExistingReferral(
        existing: ReferralRecord,
        referredUserId: Long,
        code: String,
        now: Instant,
    ) {
        requireSameCode(existing.referralCode, code)
        val inviterUserId = existing.inviterUserId
        if (inviterUserId == null) {
            ensureRewardedAttribution(null, referredUserId, code, now)
            return
        }
        if (referrals.lockUsers(listOf(inviterUserId, referredUserId).sorted()) != 2) {
            throw validation("Referral accounts are unavailable.")
        }
        val locked = referrals.lockReferralForReferredUser(referredUserId)
            ?: throw IllegalStateException("Referral disappeared while it was being redeemed.")
        redeemLockedReferral(locked, referredUserId, code, now)
    }

    private suspend fun redeemLockedReferral(
        existing: ReferralRecord,
        referredUserId: Long,
        code: String,
        now: Instant,
    ) {
        requireSameCode(existing.referralCode, code)
        val inviterUserId = existing.inviterUserId
        if (inviterUserId != null) {
            grantLocked(inviterUserId, referredUserId, code, now)
        }
        ensureRewardedAttribution(inviterUserId, referredUserId, code, now)
    }

    private suspend fun resolveExistingAttribution(
        attribution: PendingReferralAttribution,
        referredUserId: Long,
        code: String,
        now: Instant,
    ) {
        requireSameCode(attribution.referralCode, code)
        when (attribution.status) {
            PendingReferralAttributionStatus.REWARDED -> return
            PendingReferralAttributionStatus.REJECTED ->
                throw conflict("This account's signup referral attribution is no longer eligible.")
            PendingReferralAttributionStatus.PENDING -> {
                activatePendingAttribution(referredUserId, now)
                val resolved = referrals.lockPendingAttribution(referredUserId)
                    ?: throw IllegalStateException("Pending referral attribution disappeared during recovery.")
                if (resolved.status != PendingReferralAttributionStatus.REWARDED) {
                    throw conflict("This account's signup referral attribution is no longer eligible.")
                }
            }
        }
    }

    private suspend fun activeInviter(code: String): Long {
        val inviterUserId = referrals.inviterUserId(code)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        if (users.findById(inviterUserId)?.status != UserStatus.ACTIVE) {
            throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Referral code was not found.")
        }
        return inviterUserId
    }

    private suspend fun grantLocked(inviterUserId: Long, referredUserId: Long, code: String, now: Instant) {
        val referral = referrals.lockReferralForReferredUser(referredUserId)?.also {
            requireSameCode(it.referralCode, code)
        } ?: createReferral(inviterUserId, referredUserId, code, now)
        check(referral.inviterUserId == inviterUserId) { "Referral inviter changed while rewards were being granted." }

        listOf(inviterUserId, referredUserId).forEach { beneficiaryUserId ->
            val startsAt = referrals.rewardBase(beneficiaryUserId, now)
            val endsAt = startsAt.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()
            check(
                referrals.grantTier2Month(
                    referralId = referral.id,
                    beneficiaryUserId = beneficiaryUserId,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    now = now,
                ),
            ) { "Referral reward grant was not persisted." }
        }
    }

    private suspend fun createReferral(
        inviterUserId: Long,
        referredUserId: Long,
        code: String,
        now: Instant,
    ): ReferralRecord {
        val referralId = referrals.createReferral(inviterUserId, referredUserId, code, now)
        if (referralId != null) {
            return ReferralRecord(referralId, inviterUserId, referredUserId, code)
        }
        return referrals.lockReferralForReferredUser(referredUserId)?.also {
            requireSameCode(it.referralCode, code)
        } ?: throw IllegalStateException("Referral was not persisted.")
    }

    private suspend fun ensureRewardedAttribution(
        inviterUserId: Long?,
        referredUserId: Long,
        code: String,
        now: Instant,
    ) {
        if (referrals.createRewardedAttribution(inviterUserId, referredUserId, code, now)) return
        val existing = referrals.lockPendingAttribution(referredUserId)
            ?: throw IllegalStateException("Rewarded referral attribution was not persisted.")
        requireSameCode(existing.referralCode, code)
        when (existing.status) {
            PendingReferralAttributionStatus.REWARDED -> return
            PendingReferralAttributionStatus.PENDING -> check(
                referrals.completePendingAttribution(
                    attributionId = existing.id,
                    expectedStatus = PendingReferralAttributionStatus.PENDING,
                    status = PendingReferralAttributionStatus.REWARDED,
                    now = now,
                ),
            ) { "Pending referral attribution was not completed." }
            PendingReferralAttributionStatus.REJECTED ->
                throw conflict("This account's signup referral attribution is no longer eligible.")
        }
    }

    private suspend fun reject(attributionId: Long, now: Instant) {
        check(
            referrals.completePendingAttribution(
                attributionId = attributionId,
                expectedStatus = PendingReferralAttributionStatus.PENDING,
                status = PendingReferralAttributionStatus.REJECTED,
                now = now,
            ),
        ) { "Pending referral attribution was not rejected." }
    }

    private fun requireSameCode(existingCode: String, requestedCode: String) {
        if (existingCode != requestedCode) {
            throw conflict("A different referral code has already been attributed to this account.")
        }
    }

    private fun conflict(message: String) = ApiException(
        HttpStatus.CONFLICT,
        ApiErrorCode.VALIDATION_ERROR,
        message,
    )

    private fun validation(message: String) = ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ApiErrorCode.VALIDATION_ERROR,
        message,
    )
}
