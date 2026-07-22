package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import org.assertj.core.api.Assertions.assertThat
import com.buddystudy.backend.config.AdminPageController
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
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class AdminPageRouteTest : PostgresIntegrationTestSupport() {
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var controller: AdminPageController

    @Test
    fun `admin route returns Thymeleaf template`(): Unit = runBlocking {
        assertThat(controller.admin()).isEqualTo("admin/index")

        assertThat(context.getBean(SpringTemplateEngine::class.java).process("admin/index", Context()))
            .contains("BuddyStudy Admin")
            .contains("Users")
            .contains("Learning")
            .contains("Notifications")
            .contains("Quota")
            .contains("Operations")
            .contains("Average answer latency")
            .contains("Average answer rate")
            .contains("Weekly active learners")
            .contains("id=\"tooltip\"")
            .contains("data-tooltip")
    }
}
