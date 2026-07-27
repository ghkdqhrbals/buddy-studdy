package com.buddystudy.backend.profile.application.service

import com.buddystudy.backend.auth.application.port.outbound.AccountDeletionPort
import com.buddystudy.backend.profile.application.model.AccountWithdrawnEvent
import com.buddystudy.backend.profile.application.port.inbound.AccountWithdrawalCleanupUseCase
import com.buddystudy.backend.profile.application.port.outbound.ProfilePhotoStoragePort
import com.buddystudy.backend.profile.application.port.outbound.UnavailableProfilePhotoStoragePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountWithdrawalCleanupService(
    private val accountDeletion: AccountDeletionPort,
    private val profilePhotos: ProfilePhotoStoragePort = UnavailableProfilePhotoStoragePort,
) : AccountWithdrawalCleanupUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override suspend fun cleanup(event: AccountWithdrawnEvent) {
        profilePhotos.delete(event.userId)
        accountDeletion.deleteAccountData(
            userId = event.userId,
            deviceIds = event.deviceIds,
            withdrawnAt = event.withdrawnAt,
        )
        log.info(
            "account_withdrawal_cleanup_completed eventId={} userId={} deviceCount={}",
            event.eventId,
            event.userId,
            event.deviceIds.size,
        )
    }
}
