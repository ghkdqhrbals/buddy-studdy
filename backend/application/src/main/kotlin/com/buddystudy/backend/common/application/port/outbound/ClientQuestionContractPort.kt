package com.buddystudy.backend.common.application.port.outbound

import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Negotiates question representation only; account permissions remain independent. */
interface ClientQuestionContractPort {
    suspend fun currentVersion(): Int
}

object LegacyClientQuestionContract : ClientQuestionContractPort {
    override suspend fun currentVersion(): Int = 1
}

class ClientQuestionContractContext(val version: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ClientQuestionContractContext>
}

/** Used by non-HTTP adapters such as the MC-aware MCP tool boundary. */
suspend fun <T> withQuestionApiVersion(version: Int, action: suspend () -> T): T =
    withContext(ClientQuestionContractContext(version)) { action() }
