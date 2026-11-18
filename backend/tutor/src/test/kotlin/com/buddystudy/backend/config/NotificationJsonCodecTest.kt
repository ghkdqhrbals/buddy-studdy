package com.buddystudy.backend.config

import com.buddystudy.backend.common.adapter.inbound.web.ApiErrorResponseFactory
import com.buddystudy.backend.common.adapter.inbound.web.ApiLoggingPolicy
import com.buddystudy.backend.common.adapter.inbound.web.ErrorHandler
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.notification.adapter.inbound.web.NotificationController
import com.buddystudy.backend.notification.adapter.inbound.web.NotificationWebPort
import com.buddystudy.backend.notification.application.model.NotificationMutationResponse
import com.buddystudy.backend.notification.application.model.NotificationOpenSource
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.util.function.Supplier

class NotificationJsonCodecTest {
    private val notifications = Mockito.mock(NotificationWebPort::class.java)
    private val authentication = TestingAuthenticationToken("user", "credentials")
    private val contextRunner = ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CodecsAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            ),
        )
        // Production also has a Jackson 2 mapper for internal JSON; HTTP uses Jackson 3.
        .withBean(ObjectMapper::class.java, Supplier { JsonMapperProvider.mapper })
        .withBean(NotificationWebPort::class.java, Supplier { notifications })
        .withBean(NotificationController::class.java, Supplier { NotificationController(notifications) })
        .withBean(ErrorHandler::class.java, Supplier {
            ErrorHandler(ApiErrorResponseFactory(StaticMessageSource()), ApiLoggingPolicy("compact"))
        })
        .withBean(WebFilter::class.java, Supplier {
            WebFilter { exchange, chain ->
                chain.filter(exchange.mutate().principal(Mono.just(authentication)).build())
            }
        })

    @ParameterizedTest
    @EnumSource(NotificationOpenSource::class)
    fun `notification open accepts each source through auto configured HTTP codecs`(source: NotificationOpenSource) {
        runBlocking {
            Mockito.`when`(notifications.open(167L, source, authentication))
                .thenReturn(NotificationMutationResponse())
        }

        contextRunner.run { context ->
            assertThat(context).hasNotFailed()

            WebTestClient.bindToApplicationContext(context).build()
                .post().uri("/api/v1/notifications/167/open")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"source":"${source.name}"}""")
                .exchange()
                .expectStatus().isOk
                .expectBody().jsonPath("$.ok").isEqualTo(true)

            runBlocking { Mockito.verify(notifications).open(167L, source, authentication) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", """{"source":null}""", """{"source":"UNKNOWN"}"""])
    fun `invalid notification open source returns validation error without invoking the port`(body: String) {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()

            WebTestClient.bindToApplicationContext(context).build()
                .post().uri("/api/v1/notifications/167/open")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().value { assertThat(it).isEqualTo(422) }
                .expectBody().jsonPath("$.error.errorCode").isEqualTo("VALIDATION_ERROR")

            Mockito.verifyNoInteractions(notifications)
        }
    }
}
