package com.buddystudy.backend

import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.account.domain.entity.UserEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
@TestPropertySource(
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class FlywaySchemaIntegrationTest {
    @Autowired lateinit var users: UserRepository

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("buddystudy")
            .withUsername("buddystudy")
            .withPassword("buddystudy")

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) {
                postgres.start()
            }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Test
    fun `flyway schema supports user openai settings`() {
        val saved = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "flyway@example.com",
                email = "flyway@example.com",
                status = "ACTIVE",
                openaiApiKeyCipher = "cipher",
            )
        )

        assertThat(saved.id).isPositive()
        assertThat(users.findByProviderAndProviderId("EMAIL", "flyway@example.com")?.openaiApiKeyCipher).isEqualTo("cipher")
    }
}
