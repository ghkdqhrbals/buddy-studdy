package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext
import com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractPort
import kotlinx.coroutines.reactor.ReactorContext
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import kotlin.coroutines.coroutineContext

const val QUESTION_API_VERSION_HEADER = "X-BuddyStudy-API-Version"
const val QUESTION_API_CONTEXT_KEY = "buddystudy.question-api-version"

fun negotiatedQuestionApiVersion(raw: String?): Int = if (raw?.trim() == "3") 3 else 1

@Component
class ReactiveClientQuestionContractAdapter : ClientQuestionContractPort {
    override suspend fun currentVersion(): Int =
        coroutineContext[ClientQuestionContractContext]?.version
            ?: coroutineContext[ReactorContext]?.context?.getOrDefault<Int>(QUESTION_API_CONTEXT_KEY, 1)
            ?: 1
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ClientQuestionContractFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!exchange.request.path.value().startsWith("/api/")) return chain.filter(exchange)
        val version = negotiatedQuestionApiVersion(exchange.request.headers.getFirst(QUESTION_API_VERSION_HEADER))
        exchange.response.beforeCommit {
            exchange.response.headers.set(QUESTION_API_VERSION_HEADER, version.toString())
            val vary = exchange.response.headers.getOrEmpty(HttpHeaders.VARY)
                .flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty)
            if (vary.none { it == "*" || it.equals(QUESTION_API_VERSION_HEADER, ignoreCase = true) }) {
                exchange.response.headers.set(HttpHeaders.VARY, (vary + QUESTION_API_VERSION_HEADER).joinToString(", "))
            }
            Mono.empty()
        }
        return chain.filter(exchange).contextWrite { it.put(QUESTION_API_CONTEXT_KEY, version) }
    }
}
