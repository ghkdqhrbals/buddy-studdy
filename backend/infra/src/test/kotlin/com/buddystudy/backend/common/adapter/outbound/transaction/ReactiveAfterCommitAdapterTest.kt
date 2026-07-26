package com.buddystudy.backend.common.adapter.outbound.transaction

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.reactive.TransactionSynchronizationManager

class ReactiveAfterCommitAdapterTest {
    @Test
    fun `action runs only after reactive transaction commit callback`(): Unit = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()
        val adapter = ReactiveAfterCommitAdapter()

        mono {
            val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
            manager.initSynchronization()

            adapter.execute { events += "after-commit" }
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
