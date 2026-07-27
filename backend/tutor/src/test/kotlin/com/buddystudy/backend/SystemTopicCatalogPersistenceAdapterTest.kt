package com.buddystudy.backend

import com.buddystudy.backend.study.application.port.outbound.SystemTopicCatalogPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest
@TestPropertySource(
    properties = [
        "buddystudy.scheduler.enabled=false",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ],
)
class SystemTopicCatalogPersistenceAdapterTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var catalog: SystemTopicCatalogPort

    @Test
    fun `catalog suggestions are reusable and idempotent per topic path`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val rootKey = "database-$suffix"
        val pathKey = "$rootKey/indexes"
        val now = Instant.parse("2033-01-01T00:00:00Z")

        catalog.saveChildren(
            rootTopicKey = rootKey,
            parentPathKey = pathKey,
            language = "ko",
            depth = 2,
            topics = listOf("B-Tree", "Hash Index"),
            now = now,
        )
        catalog.saveChildren(
            rootTopicKey = rootKey,
            parentPathKey = pathKey,
            language = "ko",
            depth = 2,
            topics = listOf("B Tree", "Covering Index"),
            now = now.plusSeconds(1),
        )

        val children = catalog.findChildren(
            rootTopicKey = rootKey,
            parentPathKey = pathKey,
            language = "ko",
            depth = 2,
            limit = 10,
        )

        assertThat(children.map { it.topic })
            .containsExactly("B Tree", "Hash Index", "Covering Index")
    }
}
