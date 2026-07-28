package com.buddystudy.backend.settings

import kotlinx.coroutines.runBlocking

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.crypto.KeyCipher
import com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystudy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystudy.backend.settings.application.service.SettingsService
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

class SettingsServiceTest {
    private val studies = FakeStudyPort()
    private val users = FakeUserPort()
    private val properties = BuddyStudyProperties(
        crypto = BuddyStudyProperties.Crypto(masterKey = "test-master-key"),
    ).apply {
        openai.userContentApiKey = "user-content-api-key"
    }
    private val service = SettingsService(
        studies = studies,
        users = users,
        cipher = KeyCipher(properties),
        properties = properties,
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `upsert schedule loads existing studies by topics in one query`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        studies.rows += StudyEntity(id = 11, userId = 7, deviceId = "dev-1", topic = "Kotlin")

        service.upsertSchedule(
            principal,
            ScheduleCommand(
                intervalMinutes = 5,
                schedules = listOf(
                    ScheduleItemCommand(topic = "Kotlin", difficultyLevel = 4),
                    ScheduleItemCommand(topic = "Swift", difficultyLevel = 6),
                ),
            ),
        )

        assertThat(studies.findByUserIdAndTopicsCalls).isEqualTo(1)
        assertThat(studies.findByUserIdAndTopicCalls).isEqualTo(0)
        assertThat(studies.saved.map { it.topic }).containsExactly("Kotlin", "Swift")
        assertThat(studies.saved.map { it.nextDueAt }).allSatisfy { assertThat(it).isNotNull() }
    }

    @Test
    fun `upsert schedule with unchanged interval keeps existing due time`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        val existingDueAt = Instant.parse("2026-06-10T00:30:00Z")
        studies.rows += StudyEntity(
            id = 11,
            userId = 7,
            deviceId = "dev-1",
            topic = "Kotlin",
            intervalMinutes = 5,
            enabled = true,
            nextDueAt = existingDueAt,
        )

        val response = service.upsertSchedule(
            principal,
            ScheduleCommand(
                intervalMinutes = 5,
                enabled = true,
                schedules = listOf(ScheduleItemCommand(topic = "Kotlin", difficultyLevel = 6, customPrompt = "Prompt changed")),
            ),
        )

        assertThat(response.nextDueAt).isEqualTo(existingDueAt)
        assertThat(studies.rows.single().nextDueAt).isEqualTo(existingDueAt)
    }

    @Test
    fun `upsert schedule with changed interval replaces existing scheduled job`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        val existingDueAt = Instant.parse("2026-06-10T00:30:00Z")
        studies.rows += StudyEntity(
            id = 11,
            userId = 7,
            deviceId = "dev-1",
            topic = "Kotlin",
            intervalMinutes = 5,
            enabled = true,
            nextDueAt = existingDueAt,
        )

        service.upsertSchedule(
            principal,
            ScheduleCommand(
                intervalMinutes = 10,
                enabled = true,
                schedules = listOf(ScheduleItemCommand(topic = "Kotlin", difficultyLevel = 6)),
            ),
        )

        assertThat(studies.rows.single().nextDueAt).isAfter(existingDueAt)
    }

    @Test
    fun `enabling schedule repairs a tree with no active question topic`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        val root = StudyEntity(
            id = 11,
            userId = 7,
            deviceId = "dev-1",
            topic = "Redis",
            intervalMinutes = 15,
            enabled = false,
            activeForQuestions = false,
            nextDueAt = null,
            lastError = "Monthly question limit reached.",
        )
        studies.rows += root
        studies.rows += StudyEntity(
            id = 12,
            userId = 7,
            deviceId = "dev-1",
            parentStudyId = 11,
            topic = "Streams",
            activeForQuestions = false,
        )

        service.upsertSchedule(
            principal,
            ScheduleCommand(
                intervalMinutes = 15,
                enabled = true,
                schedules = listOf(ScheduleItemCommand(topic = "Redis", difficultyLevel = 6)),
            ),
        )

        assertThat(root.enabled).isTrue()
        assertThat(root.activeForQuestions).isTrue()
        assertThat(root.nextDueAt).isNotNull()
        assertThat(root.lastError).isNull()
    }

    @Test
    fun `upsert schedule without a topic does not create a default study`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")

        val response = service.upsertSchedule(
            principal,
            ScheduleCommand(topic = "", schedules = null),
        )

        assertThat(studies.rows).isEmpty()
        assertThat(studies.saved).isEmpty()
        assertThat(studies.findByUserIdAndTopicsCalls).isEqualTo(1)
        assertThat(response.nextDueAt).isNull()
    }

    @Test
    fun `upsert schedule preserves an explicitly requested SwiftUI study`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")

        service.upsertSchedule(
            principal,
            ScheduleCommand(topic = "SwiftUI", schedules = null),
        )

        assertThat(studies.saved.map { it.topic }).containsExactly("SwiftUI")
    }

    @Test
    fun `settings reports the system OpenAI key used for question generation`(): Unit = runBlocking {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        studies.rows += StudyEntity(id = 11, userId = 7, deviceId = "dev-1", topic = "Kotlin")

        val response = service.settings(principal)

        assertThat(response.openaiKeyConfigured).isTrue()
    }

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        val saved = mutableListOf<StudyEntity>()
        var findByUserIdAndTopicsCalls = 0
        var findByUserIdAndTopicCalls = 0

        override suspend fun save(entity: StudyEntity): StudyEntity {
            val persisted = if (entity.id == 0L) {
                entity.id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
                rows += entity
                entity
            } else {
                entity
            }
            saved += persisted
            return persisted
        }

        override suspend fun deleteByIdAndUserId(id: Long, userId: Long): Long = 0
        override suspend fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = rows.firstOrNull { it.userId == userId }
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? = rows.firstOrNull { it.id == id && it.userId == userId }
        override suspend fun findByUserIdAndParentStudyIdAndTopic(
            userId: Long,
            parentStudyId: Long?,
            topic: String,
        ): StudyEntity? = rows.firstOrNull {
            it.userId == userId && it.parentStudyId == parentStudyId && it.topic == topic
        }
        override suspend fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? {
            findByUserIdAndTopicCalls += 1
            return rows.firstOrNull { it.userId == userId && it.topic == topic }
        }
        override suspend fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> {
            findByUserIdAndTopicsCalls += 1
            return rows.filter { it.userId == userId && it.topic in topics }
        }
        override suspend fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId }, pageable, rows.count { it.userId == userId }.toLong())
        override suspend fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId && it.topic.contains(query) }, pageable, 0)
        override suspend fun claimDue(now: Instant, limit: Int): List<StudyEntity> = emptyList()
    }

    private class FakeUserPort : UserPort {
        var row: UserEntity? = null
        override suspend fun save(entity: UserEntity): UserEntity = entity
        override suspend fun findById(id: Long): UserEntity? = row?.takeIf { it.id == id }
        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = row?.let { mutableListOf(it) } ?: mutableListOf()
        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }
}
