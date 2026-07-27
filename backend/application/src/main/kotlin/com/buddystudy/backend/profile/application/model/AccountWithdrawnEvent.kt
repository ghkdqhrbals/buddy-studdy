package com.buddystudy.backend.profile.application.model

import java.time.Instant

data class AccountWithdrawnEvent(
    val eventId: String,
    val userId: Long,
    val deviceIds: List<String>,
    val withdrawnAt: Instant,
) {
    companion object {
        fun create(
            userId: Long,
            deviceIds: List<String>,
            withdrawnAt: Instant,
        ): AccountWithdrawnEvent =
            AccountWithdrawnEvent(
                eventId = "account-withdrawn-$userId",
                userId = userId,
                deviceIds = deviceIds.distinct(),
                withdrawnAt = withdrawnAt,
            )
    }
}
