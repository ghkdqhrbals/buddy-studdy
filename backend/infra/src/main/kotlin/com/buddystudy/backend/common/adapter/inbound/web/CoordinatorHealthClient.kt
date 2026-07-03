package com.buddystudy.backend.common.adapter.inbound.web

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

fun interface CoordinatorHealthClient {
    fun check(baseUrl: String, timeout: Duration)
}

@Component
class RestClientCoordinatorHealthClient : CoordinatorHealthClient {
    override fun check(baseUrl: String, timeout: Duration) {
        val client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(client).apply {
            setReadTimeout(timeout)
        }
        RestClient.builder()
            .requestFactory(requestFactory)
            .build()
            .get()
            .uri("${baseUrl.trim().trimEnd('/')}/coord/v1/monitoring/health")
            .retrieve()
            .toBodilessEntity()
    }
}
