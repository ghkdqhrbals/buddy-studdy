package com.buddystudy.backend

import kotlinx.coroutines.runBlocking

import com.buddystudy.backend.auth.adapter.outbound.persistence.UserRepository
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.backend.auth.adapter.outbound.persistence.DeviceRepository
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyQuestionCoveragePersistenceAdapter
import com.buddystudy.backend.study.adapter.outbound.persistence.StudyRepository
import com.buddystudy.backend.study.application.port.outbound.QuestionCoveragePort
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.flyway.locations=classpath:db/migration-mysql",
        "buddystudy.scheduler.enabled=false",
        "buddystudy.scheduler.processing-timeout-seconds=30",
        "buddystudy.streams.enabled=false",
        "buddystudy.analytics.datasource.database-name=",
        "buddystudy.crypto.master-key=test-master-key",
        "buddystudy.auth.jwt-secret=test-jwt-secret",
    ]
)
class FlywaySchemaIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var devices: DeviceRepository
    @Autowired lateinit var studies: StudyRepository
    @Autowired lateinit var questionCoverage: StudyQuestionCoveragePersistenceAdapter

    @Test
    fun `flyway schema supports user openai settings`(): Unit = runBlocking {
        val saved = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "flyway@example.com",
                email = "flyway@example.com",
                status = "ACTIVE",
                displayName = "Flyway-User-0001",
                openaiApiKeyCipher = "cipher",
            )
        )

        assertThat(saved.id).isPositive()
        assertThat(users.findByProviderAndProviderId("EMAIL", "flyway@example.com")?.openaiApiKeyCipher).isEqualTo("cipher")
    }

    @Test
    fun `registered display names are unique while anonymous buddy names can repeat`(): Unit = runBlocking {
        users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "unique-name-one@example.com",
                email = "unique-name-one@example.com",
                status = "ACTIVE",
                displayName = "Bright-Fox-4321",
            )
        )

        assertThatThrownBy {
            runBlocking {
                users.save(
                    UserEntity(
                        provider = "GOOGLE",
                        providerId = "unique-name-google",
                        email = "unique-name-two@example.com",
                        status = "PENDING_TERMS",
                        displayName = "bright-fox-4321",
                    )
                )
            }
        }.hasMessageContaining("uq_users_display_name_key")

        users.save(UserEntity(provider = "ANONYMOUS", providerId = "anonymous-one", displayName = "Buddy"))
        users.save(UserEntity(provider = "ANONYMOUS", providerId = "anonymous-two", displayName = "Buddy"))
    }

    @Test
    fun `flyway schema supports idempotent installation lookup`(): Unit = runBlocking {
        val installationKeyHash = "a".repeat(64)
        devices.save(
            DeviceEntity(
                deviceId = "flyway-installation-device",
                installationKeyHash = installationKeyHash,
                clientSecretHash = "secret-hash",
            )
        )

        assertThat(devices.findByInstallationKeyHash(installationKeyHash)?.deviceId)
            .isEqualTo("flyway-installation-device")
    }

    @Test
    fun `flyway schema supports nested question coverage tree`(): Unit = runBlocking {
        val user = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "coverage-tree@example.com",
                email = "coverage-tree@example.com",
                status = "ACTIVE",
                displayName = "Coverage-Tree-0001",
            )
        )
        val study = studies.save(
            StudyEntity(
                deviceId = "flyway-device",
                userId = user.id,
                topic = "Redis Persistence",
            )
        )

        questionCoverage.ensureCoverage(
            studyId = study.id,
            topic = study.topic,
            concepts = listOf(
                QuestionCoveragePort.CoverageConceptBlueprint(
                    key = "persistence",
                    name = "Persistence",
                    angles = emptyList(),
                    children = listOf(
                        QuestionCoveragePort.CoverageConceptBlueprint(
                            key = "aof",
                            name = "AOF",
                            angles = emptyList(),
                            children = listOf(
                                QuestionCoveragePort.CoverageConceptBlueprint(
                                    key = "recovery",
                                    name = "Recovery",
                                    angles = listOf(
                                        QuestionCoveragePort.CoverageAngleBlueprint(
                                            key = "failure_mode",
                                            name = "Failure Mode",
                                        )
                                    ),
                                )
                            ),
                        )
                    ),
                )
            ),
        )

        val selected = questionCoverage.selectNext(study.id)

        assertThat(selected?.conceptKey).isEqualTo("recovery")
        assertThat(selected?.conceptName).isEqualTo("Recovery")
        assertThat(selected?.conceptKeyPath).isEqualTo("persistence/aof/recovery")
        assertThat(selected?.conceptPath).isEqualTo("Persistence > AOF > Recovery")
        assertThat(selected?.angleKey).isEqualTo("failure_mode")
    }

    @Test
    fun `due study claim is exclusive until its lease expires`(): Unit = runBlocking {
        studies.deleteAll()
        val now = java.time.Instant.parse("2030-01-01T00:00:00Z")
        val user = users.save(
            UserEntity(
                provider = "EMAIL",
                providerId = "schedule-lease@example.com",
                email = "schedule-lease@example.com",
                status = "ACTIVE",
                displayName = "Schedule-Lease-0001",
            ),
        )
        val study = studies.save(
            StudyEntity(
                deviceId = "schedule-lease-device",
                userId = user.id,
                topic = "Schedule Lease",
                enabled = true,
                nextDueAt = now.minusSeconds(1),
            ),
        )

        val firstClaim = studies.claimDue(now, 1)
        val duplicateClaim = studies.claimDue(now.plusSeconds(29), 1)
        val reclaimed = studies.claimDue(now.plusSeconds(31), 1)

        assertThat(firstClaim.map { it.id }).containsExactly(study.id)
        assertThat(firstClaim.single().scheduleClaimedUntil).isEqualTo(now.plusSeconds(30))
        assertThat(duplicateClaim).isEmpty()
        assertThat(reclaimed.map { it.id }).containsExactly(study.id)
        assertThat(reclaimed.single().scheduleClaimedUntil).isEqualTo(now.plusSeconds(61))
    }
}
