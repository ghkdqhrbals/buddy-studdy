package com.buddystudy.backend.study.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.r2dbc.core.DatabaseClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Executes the additive DDL and catalog update in isolated H2, plus the actual study
 * backfill JOIN selection. MySQL's multi-table UPDATE syntax and locking require MySQL;
 * this fixture checks its selected rows without pretending H2 implements that mutation.
 */
@Timeout(15)
class StudyCurriculumTerminalMigrationTest {
    @Test
    fun `migration defaults unexpanded topics to false and bounds terminal backfill by owned depth`(): Unit = runBlocking {
        val database = DatabaseClient.create(ConnectionFactories.get(
            "r2dbc:h2:mem:///curriculum-terminal-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        ))
        suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
        execute("create table studies (id bigint primary key, user_id bigint not null, parent_study_id bigint)")
        execute("create table system_topic_catalog (id bigint primary key, depth int not null)")
        // Root, levels 1...5, an unexpanded shallow sibling, and an invalid cross-owner path.
        execute("insert into studies values (1,7,null),(2,7,1),(3,7,2),(4,7,3),(5,7,4),(6,7,5),(7,7,1),(8,99,4),(9,7,8)")
        execute("insert into system_topic_catalog values (1,1),(2,2),(3,3),(4,4),(5,5)")
        val migration = Files.readString(migrationPath())
        val statements = migration.lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n").split(';').map(String::trim).filter(String::isNotEmpty)
        val alters = statements.filter { it.startsWith("ALTER TABLE") }
        assertThat(alters).hasSize(2)
        alters.forEach { execute(it) }

        val existingDefaults = database.sql("select curriculum_terminal from studies order by id")
            .map { row, _ -> row.get("curriculum_terminal", Boolean::class.javaObjectType)!! }
            .all().collectList().awaitSingle()
        assertThat(existingDefaults).hasSize(9).containsOnly(false)

        val backfill = statements.single { it.startsWith("UPDATE studies AS node") }
        val joinedTables = backfill.substringAfter("UPDATE ").substringBefore("SET node.curriculum_terminal")
        val ids = database.sql("select node.id from $joinedTables order by node.id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .all().collectList().awaitSingle()
        assertThat(ids).containsExactly(5L, 6L)
        execute(statements.single { it.startsWith("UPDATE system_topic_catalog") })
        val catalog = database.sql("select curriculum_terminal from system_topic_catalog order by id")
            .map { row, _ -> row.get("curriculum_terminal", Boolean::class.javaObjectType)!! }
            .all().collectList().awaitSingle()
        assertThat(catalog).containsExactly(false, false, false, true, true)
        execute("insert into studies(id,user_id,parent_study_id) values(10,7,1)")
        val futureDefault = database.sql("select curriculum_terminal from studies where id = 10")
            .map { row, _ -> row.get("curriculum_terminal", Boolean::class.javaObjectType)!! }
            .one().awaitSingle()
        assertThat(futureDefault).isFalse()
    }

    private fun migrationPath(): Path = listOf(
        Path.of("../tutor/src/main/resources/db/migration-mysql/V119__study_curriculum_terminal.sql"),
        Path.of("tutor/src/main/resources/db/migration-mysql/V119__study_curriculum_terminal.sql"),
        Path.of("backend/tutor/src/main/resources/db/migration-mysql/V119__study_curriculum_terminal.sql"),
    ).first { Files.isRegularFile(it) }
}
