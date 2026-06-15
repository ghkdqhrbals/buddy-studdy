package com.buddystuddy.backend.settings

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleCommand
import com.buddystuddy.backend.settings.application.port.inbound.ScheduleItemCommand
import com.buddystuddy.backend.settings.application.service.SettingsService
import com.buddystuddy.backend.study.application.port.outbound.StudyPort
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
    private val users = FakeUserPort()
    private val service = SettingsService(
        studies = studies,
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
        override fun findDue(now: Instant, pageable: Pageable): List<StudyEntity> = emptyList()
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
