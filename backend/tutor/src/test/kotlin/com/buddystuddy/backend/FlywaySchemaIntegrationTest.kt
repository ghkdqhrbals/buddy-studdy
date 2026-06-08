package com.buddystuddy.backend

import com.buddystuddy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystuddy.domain.UserEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-flyway;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class FlywaySchemaIntegrationTest {
    @Autowired lateinit var users: UserRepository

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
