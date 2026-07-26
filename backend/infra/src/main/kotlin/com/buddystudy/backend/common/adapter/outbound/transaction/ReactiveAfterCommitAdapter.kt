package com.buddystudy.backend.common.adapter.outbound.transaction

import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

@Component
class ReactiveAfterCommitAdapter : AfterCommitPort {
    override suspend fun execute(action: suspend () -> Unit) {
        val manager = try {
            TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        } catch (_: NoTransactionException) {
            action()
            return
        }

        if (!manager.isSynchronizationActive) {
            action()
            return
        }

        manager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit(): Mono<Void> = mono { action() }.then()
            },
        )
    }
}
