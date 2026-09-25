package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class RevenueCatLocalStoreKitMigrationIntegrationTest {
    @Test
    fun `V123 retires only unresolved local StoreKit receipts and preserves real sandbox history`() {
        val localPort = System.getenv("BUDDYSTUDY_TEST_MYSQL_PORT")?.toInt()?.also {
            require(it in 1024..65535 && it != 3306)
        }
        val database = "buddystudy_rc_${UUID.randomUUID().toString().replace("-", "")}"
        val mysql: MySQLContainer<*>? = if (localPort == null) MySQLContainer("mysql:8.4")
            .withDatabaseName(database).withUsername("buddystudy").withPassword("buddystudy").also { it.start() }
        else null
        val adminUrl = "jdbc:mysql://127.0.0.1:$localPort/?serverTimezone=UTC"
        val url = mysql?.jdbcUrl ?: "jdbc:mysql://127.0.0.1:$localPort/$database?serverTimezone=UTC"
        val user = mysql?.username ?: "root"
        val password = mysql?.password ?: ""
        try {
            if (localPort != null) DriverManager.getConnection(adminUrl, user, password).use {
                it.execute("create database $database character set utf8mb4 collate utf8mb4_0900_ai_ci")
            }
            fun migrate(target: String) = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration-mysql").target(MigrationVersion.fromVersion(target)).load().migrate()
            migrate("122")
            DriverManager.getConnection(url, user, password).use { connection ->
                val localId = "StoreKitTest_Transaction_7e292154fceb3b8b0b4be1f8a05cf19c_0"
                val cases = listOf(
                    Receipt("pending", localId, ignored = true),
                    Receipt("processing", localId, status = "PROCESSING", ignored = true),
                    Receipt("failed", localId, status = "FAILED", ignored = true),
                    Receipt("exhausted", localId, status = "EXHAUSTED", ignored = true),
                    Receipt("lifecycle-original", "200000000000010", original = localId, ignored = true),
                    Receipt("real-sandbox", "200000000000011"),
                    Receipt("real-unmapped", "200000000000012", status = "EXHAUSTED"),
                    Receipt("underscore-lookalike", "StoreKitTestXTransactionY123"),
                    Receipt("case-lookalike", "storekittest_transaction_123"),
                    Receipt("production", localId, environment = "PRODUCTION"),
                    Receipt("test-store", localId, store = "TEST_STORE"),
                    Receipt("completed", localId, status = "COMPLETED"),
                    Receipt("apple-provider", localId, provider = "APPLE"),
                )
                cases.forEach { connection.insertReceipt(it) }
                assertThat(migrate("123").migrationsExecuted).isEqualTo(1)
                cases.forEach { receipt ->
                    connection.prepareStatement("select processing_status, attempt_count, last_error from subscription_events where provider_event_id = ?").use { statement ->
                        statement.setString(1, receipt.id)
                        statement.executeQuery().use { row ->
                            assertThat(row.next()).isTrue()
                            assertThat(row.getString("processing_status")).describedAs(receipt.id)
                                .isEqualTo(if (receipt.ignored) "IGNORED" else receipt.status)
                            assertThat(row.getInt("attempt_count")).isEqualTo(if (receipt.status == "EXHAUSTED") 3 else 0)
                            if (receipt.ignored) assertThat(row.getString("last_error")).contains("Xcode StoreKit test transaction")
                        }
                    }
                    if (receipt.provider == "REVENUECAT") connection.prepareStatement("select processing_status from billing_revenuecat_event_inbox where event_id = ?").use { statement ->
                        statement.setString(1, receipt.id)
                        statement.executeQuery().use { row ->
                            assertThat(row.next()).isTrue()
                            assertThat(row.getString("processing_status")).describedAs(receipt.id)
                                .isEqualTo(if (receipt.ignored) "IGNORED" else receipt.inboxStatus)
                        }
                    }
                }
            }
        } finally {
            if (localPort != null) DriverManager.getConnection(adminUrl, user, password).use {
                it.execute("drop database if exists $database")
            }
            mysql?.stop()
        }
    }

    private data class Receipt(
        val id: String,
        val transaction: String,
        val original: String = transaction,
        val status: String = "PENDING",
        val environment: String = "SANDBOX",
        val store: String = "APP_STORE",
        val provider: String = "REVENUECAT",
        val ignored: Boolean = false,
    ) {
        val inboxStatus: String get() = when (status) {
            "COMPLETED" -> "PROCESSED"
            "EXHAUSTED", "FAILED" -> status
            else -> "RECEIVED"
        }
    }

    private fun Connection.insertReceipt(receipt: Receipt) {
        // Each transaction remains unique while preserving the exact local-test prefix.
        val transaction = if (receipt.transaction.all(Char::isDigit)) receipt.transaction else "${receipt.transaction}-${receipt.id}"
        val eventType = if (receipt.id == "lifecycle-original") "EXPIRATION" else "INITIAL_PURCHASE"
        prepareStatement("""
            insert into subscription_events (
                provider_event_id, provider, event_type, store, transaction_id, original_transaction_id,
                environment, processing_status, attempt_count, max_attempts, next_attempt_at,
                payload_sha256, occurred_at, created_at, updated_at
            ) values (?, ?, '$eventType', ?, ?, ?, ?, ?, ?, 3,
                utc_timestamp(6), ?, utc_timestamp(6), utc_timestamp(6), utc_timestamp(6))
        """.trimIndent()).use { statement ->
            statement.setString(1, receipt.id)
            statement.setString(2, receipt.provider)
            statement.setString(3, receipt.store)
            statement.setString(4, transaction)
            statement.setString(5, receipt.original)
            statement.setString(6, receipt.environment)
            statement.setString(7, receipt.status)
            statement.setInt(8, if (receipt.status == "EXHAUSTED") 3 else 0)
            statement.setString(9, "f".repeat(64))
            statement.executeUpdate()
        }
        if (receipt.provider != "REVENUECAT") return
        prepareStatement("""
            insert into billing_revenuecat_event_inbox (
                event_id, event_type, app_user_id, store, transaction_id, environment,
                signed_payload_sha256, processing_status, event_at, received_at, updated_at
            ) values (?, '$eventType', '${'$'}RCAnonymousID:local', ?, ?, ?, ?, ?,
                utc_timestamp(6), utc_timestamp(6), utc_timestamp(6))
        """.trimIndent()).use { statement ->
            statement.setString(1, receipt.id)
            statement.setString(2, receipt.store)
            statement.setString(3, transaction)
            statement.setString(4, receipt.environment)
            statement.setString(5, "f".repeat(64))
            statement.setString(6, receipt.inboxStatus)
            statement.executeUpdate()
        }
    }

    private fun Connection.execute(sql: String): Int = createStatement().use { it.executeUpdate(sql) }
}
