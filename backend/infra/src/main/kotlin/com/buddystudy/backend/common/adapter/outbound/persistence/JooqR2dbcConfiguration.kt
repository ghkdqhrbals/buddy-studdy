package com.buddystudy.backend.common.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.connection.ConnectionFactoryUtils
import org.springframework.transaction.NoTransactionException

@Configuration(proxyBeanMethods = false)
class JooqR2dbcConfiguration {
    @Bean
    fun jooqR2dbcExecutor(connectionFactory: ConnectionFactory): JooqR2dbcExecutor {
        val dialect = if (connectionFactory.metadata.name.contains("H2", ignoreCase = true)) {
            SQLDialect.H2
        } else {
            SQLDialect.POSTGRES
        }
        return JooqR2dbcExecutor(connectionFactory, dialect)
    }
}

class JooqR2dbcExecutor(
    private val connectionFactory: ConnectionFactory,
    private val dialect: SQLDialect,
) {
    suspend fun <T> withDsl(block: suspend (DSLContext) -> T): T {
        val transactionBound = try {
            ConnectionFactoryUtils.currentConnectionFactory(connectionFactory)
                .hasElement()
                .awaitSingle()
        } catch (_: NoTransactionException) {
            false
        }
        val connection = ConnectionFactoryUtils.getConnection(connectionFactory).awaitSingle()
        return try {
            block(DSL.using(connection, dialect))
        } finally {
            if (!transactionBound) {
                withContext(NonCancellable) {
                    ConnectionFactoryUtils.releaseConnection(connection, connectionFactory).awaitFirstOrNull()
                }
            }
        }
    }
}
