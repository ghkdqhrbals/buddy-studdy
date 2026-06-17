package com.buddystuddy.backend.settings

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.service.SettingsService
import com.buddystuddy.backend.study.application.port.outbound.StudyQuestionJobPort
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
import com.buddystuddy.study.domain.entity.StudyQuestionJobEntity
import com.buddystuddy.study.domain.entity.StudyQuestionJobStatus
import com.buddystuddy.study.domain.entity.StudyEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

class SettingsServiceTest {
    private val studies = FakeStudyPort()
    private val jobs = FakeStudyQuestionJobPort()
    private val users = FakeUserPort()
    private val service = SettingsService(
        studies = studies,
        jobs = jobs,
        users = users,
        cipher = KeyCipher(BuddyStuddyProperties(crypto = BuddyStuddyProperties.Crypto(masterKey = "test-master-key"))),
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `upsert schedule loads existing studies by topics in one query`() {
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
        assertThat(jobs.rows.filter { it.status == StudyQuestionJobStatus.SCHEDULED }.map { it.studyId }).containsExactly(11, 12)
    }

    @Test
    fun `upsert schedule with unchanged interval keeps existing due time`() {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        val existingDueAt = Instant.parse("2026-06-10T00:30:00Z")
        studies.rows += StudyEntity(
            id = 11,
            userId = 7,
            deviceId = "dev-1",
            topic = "Kotlin",
            intervalMinutes = 5,
            enabled = true,
        )
        jobs.rows += StudyQuestionJobEntity(
            id = 21,
            studyId = 11,
            userId = 7,
            deviceId = "dev-1",
            scheduledAt = existingDueAt,
            status = StudyQuestionJobStatus.SCHEDULED,
            createdAt = Instant.parse("2026-06-10T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-10T00:00:00Z"),
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
        assertThat(jobs.rows).hasSize(1)
        assertThat(jobs.rows.single().status).isEqualTo(StudyQuestionJobStatus.SCHEDULED)
        assertThat(jobs.rows.single().scheduledAt).isEqualTo(existingDueAt)
    }

    @Test
    fun `upsert schedule with changed interval replaces existing scheduled job`() {
        users.row = UserEntity(id = 7, providerId = "u7", status = "ACTIVE")
        val existingDueAt = Instant.parse("2026-06-10T00:30:00Z")
        studies.rows += StudyEntity(
            id = 11,
            userId = 7,
            deviceId = "dev-1",
            topic = "Kotlin",
            intervalMinutes = 5,
            enabled = true,
        )
        jobs.rows += StudyQuestionJobEntity(
            id = 21,
            studyId = 11,
            userId = 7,
            deviceId = "dev-1",
            scheduledAt = existingDueAt,
            status = StudyQuestionJobStatus.SCHEDULED,
            createdAt = Instant.parse("2026-06-10T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-10T00:00:00Z"),
        )

        service.upsertSchedule(
            principal,
            ScheduleCommand(
                intervalMinutes = 10,
                enabled = true,
                schedules = listOf(ScheduleItemCommand(topic = "Kotlin", difficultyLevel = 6)),
            ),
        )

        assertThat(jobs.rows.map { it.status }).containsExactly(StudyQuestionJobStatus.CANCELED, StudyQuestionJobStatus.SCHEDULED)
        assertThat(jobs.rows.last().scheduledAt).isAfter(existingDueAt)
    }

    private class FakeStudyPort : StudyPort {
        val rows = mutableListOf<StudyEntity>()
        val saved = mutableListOf<StudyEntity>()
        var findByUserIdAndTopicsCalls = 0
        var findByUserIdAndTopicCalls = 0

        override fun save(entity: StudyEntity): StudyEntity {
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

        override fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): StudyEntity? = rows.firstOrNull { it.userId == userId }
        override fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? = rows.firstOrNull { it.id == id && it.userId == userId }
        override fun findByUserIdAndTopic(userId: Long, topic: String): StudyEntity? {
            findByUserIdAndTopicCalls += 1
            return rows.firstOrNull { it.userId == userId && it.topic == topic }
        }
        override fun findByUserIdAndTopics(userId: Long, topics: Collection<String>): List<StudyEntity> {
            findByUserIdAndTopicsCalls += 1
            return rows.filter { it.userId == userId && it.topic in topics }
        }
        override fun findByUserId(userId: Long, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId }, pageable, rows.count { it.userId == userId }.toLong())
        override fun findByUserIdAndQuery(userId: Long, query: String, pageable: Pageable): Page<StudyEntity> =
            PageImpl(rows.filter { it.userId == userId && it.topic.contains(query) }, pageable, 0)
    }

    private class FakeStudyQuestionJobPort : StudyQuestionJobPort {
        val rows = mutableListOf<StudyQuestionJobEntity>()
        override fun save(entity: StudyQuestionJobEntity): StudyQuestionJobEntity {
            if (entity.id == 0L) {
                entity.id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
                rows += entity
            }
            return entity
        }
        override fun saveBatch(entities: Iterable<StudyQuestionJobEntity>): List<StudyQuestionJobEntity> =
            entities.map { save(it) }
        override fun findLatestByStudyId(studyId: Long): StudyQuestionJobEntity? =
            rows.filter { it.studyId == studyId }.maxByOrNull { it.id }
        override fun findLatestByStudyIds(studyIds: Collection<Long>): List<StudyQuestionJobEntity> =
            rows.filter { it.studyId in studyIds }
        override fun claimDue(now: Instant, limit: Int): List<StudyQuestionJobEntity> = emptyList()
        override fun cancelScheduledByStudyId(studyId: Long, now: Instant): Int {
            val targets = rows.filter { it.studyId == studyId && it.status == StudyQuestionJobStatus.SCHEDULED }
            targets.forEach { it.status = StudyQuestionJobStatus.CANCELED }
            return targets.size
        }
        override fun recoverStaleProcessing(before: Instant, now: Instant): Int = 0
    }

    private class FakeUserPort : UserPort {
        var row: UserEntity? = null
        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> = Optional.ofNullable(row?.takeIf { it.id == id })
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> = row?.let { mutableListOf(it) } ?: mutableListOf()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }
}
