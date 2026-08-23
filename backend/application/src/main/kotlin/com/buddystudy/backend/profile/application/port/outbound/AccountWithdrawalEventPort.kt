package com.buddystudy.backend.profile.application.port.outbound

import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent

interface AccountWithdrawalEventPort {
    suspend fun append(event: AccountWithdrawnEvent): Long
}
