package com.buddystudy.backend.common.application.privacy

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Propagates through coroutine children/dispatchers and is restored on every exit. */
object PrivateLearningContentLogScope {
    private val active = ThreadLocal.withInitial { false }

    fun isActive(): Boolean = active.get()

    suspend fun <T> protecting(block: suspend () -> T): T =
        withContext(active.asContextElement(true)) { block() }
}
