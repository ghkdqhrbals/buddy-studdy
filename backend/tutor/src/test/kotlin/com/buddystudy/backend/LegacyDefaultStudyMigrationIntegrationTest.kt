package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

class LegacyDefaultStudyMigrationIntegrationTest {
    @Test
    fun `V78 removes only unused localized fallback studies during upgrade`() {
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
            .withDatabaseName("buddystudy_legacy_study_migration")
            .withUsername("buddystudy")
            .withPassword("buddystudy")

        mysql.start()
        try {
            flyway(mysql, target = "77").migrate()

            val seeded = mysql.connection().use(::seedUpgradeState)
            val migrationResult = flyway(mysql, target = "78").migrate()

            mysql.connection().use { connection ->
                val remainingStudyIds = connection.studyIds()
                val removedStudyIds = seeded.allStudyIds - remainingStudyIds

                assertThat(migrationResult.migrationsExecuted).isEqualTo(1)
                assertThat(removedStudyIds).containsExactlyInAnyOrderElementsOf(seeded.removableStudyIds)
                assertThat(remainingStudyIds).containsAll(seeded.preservedStudyIds)
                assertThat(connection.count("study_question_jobs", seeded.jobId)).isEqualTo(1)
                assertThat(connection.count("questions", seeded.questionId)).isEqualTo(1)
                assertThat(connection.questionStudyId(seeded.questionId)).isEqualTo(seeded.questionStudyId)
                assertThat(connection.count("studies", seeded.childStudyId)).isEqualTo(1)
            }
        } finally {
            mysql.stop()
        }
    }

    private fun flyway(mysql: MySQLContainer<*>, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration-mysql")
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target))
        }
        return configuration.load()
    }

    private fun MySQLContainer<*>.connection(): Connection =
        DriverManager.getConnection(jdbcUrl, username, password)

    private fun seedUpgradeState(connection: Connection): SeededUpgradeState {
        connection.autoCommit = false
        try {
            val ownerId = connection.insertUser("with-real-study")
            val removableKorean = connection.insertStudy(
                userId = ownerId,
                topic = "내 학습",
                customPrompt = IOS_DEFAULT_PROMPT,
            )
            val removableEnglish = connection.insertStudy(
                userId = ownerId,
                topic = "My Study",
                customPrompt = BACKEND_DEFAULT_PROMPT,
            )
            val removableJapanese = connection.insertStudy(
                userId = ownerId,
                topic = "マイ学習",
                customPrompt = IOS_DEFAULT_PROMPT,
            )
            val jobStudyId = connection.insertStudy(ownerId, "내 학습", IOS_DEFAULT_PROMPT)
            val questionStudyId = connection.insertStudy(ownerId, "My Study", BACKEND_DEFAULT_PROMPT)
            val parentWithChildId = connection.insertStudy(ownerId, "マイ学習", IOS_DEFAULT_PROMPT)
            val changedPromptStudyId = connection.insertStudy(
                userId = ownerId,
                topic = "내 학습",
                customPrompt = "Ask about the learner's selected subject.",
            )
            val childStudyId = connection.insertStudy(
                userId = ownerId,
                topic = "Child Topic",
                customPrompt = IOS_DEFAULT_PROMPT,
                parentStudyId = parentWithChildId,
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
            )
            val realStudyId = connection.insertStudy(
                userId = ownerId,
                topic = "English",
                customPrompt = IOS_DEFAULT_PROMPT,
                difficultyLevel = 4,
                createdAt = Instant.parse("2026-08-10T00:00:00Z"),
            )
            val jobId = connection.insertStudyQuestionJob(ownerId, jobStudyId)
            val questionId = connection.insertQuestion(ownerId, questionStudyId)

            val standaloneGhostOwnerId = connection.insertUser("without-real-study")
            val standaloneGhostStudyId = connection.insertStudy(
                userId = standaloneGhostOwnerId,
                topic = "내 학습",
                customPrompt = IOS_DEFAULT_PROMPT,
            )

            connection.commit()

            val removableStudyIds = setOf(
                removableKorean,
                removableEnglish,
                removableJapanese,
                standaloneGhostStudyId,
            )
            val preservedStudyIds = setOf(
                jobStudyId,
                questionStudyId,
                parentWithChildId,
                changedPromptStudyId,
                childStudyId,
                realStudyId,
            )
            return SeededUpgradeState(
                removableStudyIds = removableStudyIds,
                preservedStudyIds = preservedStudyIds,
                allStudyIds = removableStudyIds + preservedStudyIds,
                jobId = jobId,
                questionId = questionId,
                questionStudyId = questionStudyId,
                childStudyId = childStudyId,
            )
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun Connection.insertUser(suffix: String): Long {
        val sql = """
            insert into users (
                provider, provider_id, status, email, display_name, created_at, updated_at
            ) values (
                'EMAIL', ?, 'ACTIVE', ?, ?, utc_timestamp(6), utc_timestamp(6)
            )
        """.trimIndent()
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setString(1, "legacy-study-$suffix")
            statement.setString(2, "legacy-study-$suffix@example.com")
            statement.setString(3, "Legacy Study $suffix")
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated user id." }
                keys.getLong(1)
            }
        }
    }

    private fun Connection.insertStudy(
        userId: Long,
        topic: String,
        customPrompt: String,
        parentStudyId: Long? = null,
        difficultyLevel: Int = 2,
        createdAt: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    ): Long {
        val sql = """
            insert into studies (
                device_id, user_id, parent_study_id, sort_order, topic,
                difficulty_level, interval_minutes, enabled, active_for_questions,
                notification_sound, custom_prompt, openai_model, max_history_count,
                next_due_at, schedule_claimed_until, last_sent_at, last_error,
                created_at, updated_at
            ) values (
                ?, ?, ?, 0, ?,
                ?, 15, true, true,
                'default', ?, 'gpt-5.4', 100,
                ?, null, null, null,
                ?, ?
            )
        """.trimIndent()
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            val timestamp = Timestamp.from(createdAt)
            statement.setString(1, "legacy-study-device-$userId")
            statement.setLong(2, userId)
            if (parentStudyId == null) {
                statement.setNull(3, Types.BIGINT)
            } else {
                statement.setLong(3, parentStudyId)
            }
            statement.setString(4, topic)
            statement.setInt(5, difficultyLevel)
            statement.setString(6, customPrompt)
            statement.setTimestamp(7, Timestamp.from(createdAt.plusSeconds(15 * 60)))
            statement.setTimestamp(8, timestamp)
            statement.setTimestamp(9, timestamp)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated study id." }
                keys.getLong(1)
            }
        }
    }

    private fun Connection.insertStudyQuestionJob(userId: Long, studyId: Long): Long {
        val sql = """
            insert into study_question_jobs (
                study_id, device_id, user_id, scheduled_at, status, created_at, updated_at
            ) values (
                ?, ?, ?, ?, 'SCHEDULED', ?, ?
            )
        """.trimIndent()
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            val timestamp = Timestamp.from(Instant.parse("2026-08-01T00:15:00Z"))
            statement.setLong(1, studyId)
            statement.setString(2, "legacy-study-device-$userId")
            statement.setLong(3, userId)
            statement.setTimestamp(4, timestamp)
            statement.setTimestamp(5, timestamp)
            statement.setTimestamp(6, timestamp)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated study job id." }
                keys.getLong(1)
            }
        }
    }

    private fun Connection.insertQuestion(userId: Long, studyId: Long): Long {
        val sql = """
            insert into questions (
                device_id, user_id, study_id, question, topic, difficulty_level,
                scheduled_for, status, source, is_public, source_language,
                created_at, updated_at
            ) values (
                ?, ?, ?, 'Existing question', 'My Study', 2,
                ?, 'ungraded', 'manual', false, 'en',
                ?, ?
            )
        """.trimIndent()
        return prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            val timestamp = Timestamp.from(Instant.parse("2026-08-01T00:10:00Z"))
            statement.setString(1, "legacy-study-device-$userId")
            statement.setLong(2, userId)
            statement.setLong(3, studyId)
            statement.setTimestamp(4, timestamp)
            statement.setTimestamp(5, timestamp)
            statement.setTimestamp(6, timestamp)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Expected a generated question id." }
                keys.getLong(1)
            }
        }
    }

    private fun Connection.studyIds(): Set<Long> =
        createStatement().use { statement ->
            statement.executeQuery("select id from studies").use { rows ->
                buildSet {
                    while (rows.next()) {
                        add(rows.getLong(1))
                    }
                }
            }
        }

    private fun Connection.count(table: String, id: Long): Int =
        prepareStatement("select count(*) from $table where id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Expected a count row for $table." }
                rows.getInt(1)
            }
        }

    private fun Connection.questionStudyId(questionId: Long): Long? =
        prepareStatement("select study_id from questions where id = ?").use { statement ->
            statement.setLong(1, questionId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Expected question $questionId to remain." }
                rows.getObject(1, java.lang.Long::class.java)?.toLong()
            }
        }

    private data class SeededUpgradeState(
        val removableStudyIds: Set<Long>,
        val preservedStudyIds: Set<Long>,
        val allStudyIds: Set<Long>,
        val jobId: Long,
        val questionId: Long,
        val questionStudyId: Long,
        val childStudyId: Long,
    )

    private companion object {
        const val IOS_DEFAULT_PROMPT = "짧고 명확하게 질문하세요. 사용자가 답하기 좋은 한 문제만 내세요."
        const val BACKEND_DEFAULT_PROMPT =
            "Ask one short, clear study question at a time. Keep it focused so the learner can answer it directly."
    }
}
