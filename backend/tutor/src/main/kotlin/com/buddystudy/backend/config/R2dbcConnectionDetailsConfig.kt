package com.buddystudy.backend.config

import io.r2dbc.spi.ConnectionFactoryOptions
import org.springframework.boot.r2dbc.autoconfigure.R2dbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class R2dbcConnectionDetailsConfig {
    @Bean
    fun r2dbcConnectionDetails(environment: Environment): R2dbcConnectionDetails =
        EnvironmentR2dbcConnectionDetails(environment)
}

internal class EnvironmentR2dbcConnectionDetails(
    private val environment: Environment,
) : R2dbcConnectionDetails {
    override fun getConnectionFactoryOptions(): ConnectionFactoryOptions {
        val url = environment.requiredNonBlank("spring.r2dbc.url", "R2DBC_DATABASE_URL")
        val username = environment.firstNonBlank("spring.r2dbc.username", "DATABASE_USERNAME")
        val password = environment.firstDefined("spring.r2dbc.password", "DATABASE_PASSWORD")
        val parsed = ConnectionFactoryOptions.parse(url)
        val builder = parsed.mutate()

        if (!parsed.hasOption(ConnectionFactoryOptions.USER) && username != null) {
            builder.option(ConnectionFactoryOptions.USER, username)
        }
        if (!parsed.hasOption(ConnectionFactoryOptions.PASSWORD) && password != null) {
            builder.option(ConnectionFactoryOptions.PASSWORD, password)
        }
        return builder.build()
    }

    private fun Environment.requiredNonBlank(vararg names: String): String =
        firstNonBlank(*names)
            ?: error("R2DBC database URL is required.")

    private fun Environment.firstNonBlank(vararg names: String): String? =
        names.firstNotNullOfOrNull { getProperty(it)?.takeIf(String::isNotBlank) }

    private fun Environment.firstDefined(vararg names: String): String? =
        names.firstNotNullOfOrNull(::getProperty)
}
