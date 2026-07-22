package com.buddystudy.backend.common.application.transaction

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.reactive.TransactionSynchronizationManager

class ReactiveAfterCommitTest {
    @Test
    fun `action runs only after reactive transaction commit callback`(): Unit = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()

        mono {
            val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
            manager.initSynchronization()

            afterReactiveCommit { events += "after-commit" }
            events += "inside-transaction"

            assertThat(events).containsExactly("inside-transaction")
            manager.synchronizations.single().afterCommit().awaitSingleOrNull()
            Unit
        }
            .contextWrite(TransactionContextManager.createTransactionContext())
            .awaitSingle()

        assertThat(events).containsExactly("inside-transaction", "after-commit")
    }
}
