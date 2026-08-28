package com.buddystudy.backend.profile.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import org.springframework.http.HttpStatus

object ReferralCodePolicy {
    private val format = Regex("^BS-[A-Z2-9]{8}$")

    fun normalizeOrNull(code: String): String? = code.trim().uppercase().takeIf(format::matches)

    fun normalize(code: String): String {
        return normalizeOrNull(code) ?: run {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.VALIDATION_ERROR,
                "Referral code is invalid.",
            )
        }
    }
}
