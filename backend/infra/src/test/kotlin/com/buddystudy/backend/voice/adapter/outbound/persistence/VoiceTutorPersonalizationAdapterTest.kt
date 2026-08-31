package com.buddystudy.backend.voice.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

class VoiceTutorPersonalizationAdapterTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///voice-personalization-${UUID.randomUUID()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = VoiceTutorPersonalizationAdapter(database)

    @Test
    fun `graded question evidence excludes voice spoken scores and foreign nodes while preserving profile context`(): Unit = runBlocking {
        schema()
        execute("insert into user_learning_contexts values (7, '사용자 학습 배경', '[\"Redis\",\"네트워크\"]')")
        execute("insert into questions values (1,7,50,'QUESTION','Ordinary graded question','Learner answer','Ordinary feedback',90,'2026-08-30 01:00:00+00','2026-08-30 01:00:00+00','2026-08-30 00:00:00+00',null)")
        execute("insert into questions values (2,7,50,'VOICE_TUTOR','Voice spoken question','Voice spoken answer','85점',85,'2026-08-31 01:00:00+00','2026-08-31 01:00:00+00','2026-08-31 00:00:00+00',null)")
        execute("insert into questions values (3,8,50,'QUESTION','Foreign owner','No','No',99,null,null,'2026-08-31 00:00:00+00',null)")
        execute("insert into questions values (4,7,51,'QUESTION','Different node','No','No',99,null,null,'2026-08-31 00:00:00+00',null)")
        execute("insert into questions values (5,7,50,'QUESTION','Deleted question','No','No',99,null,null,'2026-08-31 00:00:00+00','2026-08-31 02:00:00+00')")
        execute("insert into questions values (6,7,50,'QUESTION','Pending question',null,null,null,null,null,'2026-08-31 00:00:00+00',null)")

        val result = adapter.load(7, 50)

        assertThat(result.resumeMarkdown).isEqualTo("사용자 학습 배경")
        assertThat(result.interests).containsExactly("Redis", "네트워크")
        assertThat(result.recentLearningEvidence).containsExactly("Question: Ordinary graded question | Learner answer: Learner answer | Score: 90 | Feedback: Ordinary feedback")
        assertThat(adapter.load(7, 0).recentLearningEvidence).isEmpty()
    }

    @Test
    fun `many recent voice records cannot consume the ten ordinary evidence slots`(): Unit = runBlocking {
        schema()
        (1..12).forEach { id ->
            execute("insert into questions values ($id,7,50,'QUESTION','Ordinary $id','Answer',null,90,null,null,'2026-08-30 00:00:00+00',null)")
            execute("insert into questions values (${id + 100},7,50,'VOICE_TUTOR','Voice $id','Answer','85점',85,null,null,'2026-08-31 00:00:00+00',null)")
        }

        val result = adapter.load(7, 50)

        assertThat(result.recentLearningEvidence).hasSize(10)
        assertThat(result.recentLearningEvidence.first()).startsWith("Question: Ordinary 12 |")
        assertThat(result.recentLearningEvidence.last()).startsWith("Question: Ordinary 3 |")
        assertThat(result.recentLearningEvidence).allMatch { it.startsWith("Question: Ordinary ") }
    }

    private suspend fun schema() {
        execute("create table user_learning_contexts (user_id bigint primary key, resume_markdown clob, interests_json clob)")
        execute("create table questions (id bigint primary key, user_id bigint, study_id bigint, record_type varchar(24), question clob, answer clob, feedback clob, score integer, graded_at timestamp with time zone, answered_at timestamp with time zone, created_at timestamp with time zone, deleted_at timestamp with time zone)")
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
}
