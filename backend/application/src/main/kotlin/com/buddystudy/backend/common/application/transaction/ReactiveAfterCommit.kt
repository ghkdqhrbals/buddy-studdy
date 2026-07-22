package com.buddystudy.backend.common.application.transaction

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

suspend fun afterReactiveCommit(action: suspend () -> Unit) {
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
