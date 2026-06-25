package com.buddystuddy.backend

import org.assertj.core.api.Assertions.assertThat
import com.buddystuddy.backend.config.AdminPageController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-admin-page;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ],
)
class AdminPageRouteTest {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var controller: AdminPageController

    @Test
    fun `admin route returns Thymeleaf template`() {
        assertThat(controller.admin()).isEqualTo("admin/index")

        assertThat(context.getBean(SpringTemplateEngine::class.java).process("admin/index", Context()))
            .contains("BuddyStuddy Admin")
            .contains("Average answer latency")
            .contains("Average answer rate")
            .contains("Weekly active learners")
            .contains("<title>")
    }
}
