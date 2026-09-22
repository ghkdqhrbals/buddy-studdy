package com.buddystudy.backend.community.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient

class PublicQuestionSharePersistenceAdapterTest {
    private val database = DatabaseClient.create(ConnectionFactories.get(
        "r2dbc:h2:mem:///public-question-share;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ))
    private val adapter = PublicQuestionSharePersistenceAdapter(database)

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        listOf("question_stats", "question_search", "questions", "voice_study_learning_records", "users").forEach { execute("drop table if exists $it") }
        execute("create table users (id bigint primary key, allow_public_questions boolean, display_name varchar(255))")
        execute("create table questions (id bigint primary key, user_id bigint, topic varchar(255), question varchar(1000), source_language varchar(10), is_public boolean, deleted_at timestamp, status varchar(20), answer varchar(1000), score integer, record_type varchar(20), voice_record_id bigint)")
        execute("create table voice_study_learning_records (id bigint primary key, user_id bigint)")
        execute("create table question_search (question_id bigint, language varchar(10), topic varchar(255), question varchar(1000), primary key(question_id,language))")
        execute("create table question_stats (question_id bigint primary key, view_count bigint)")
        execute("insert into users values (1, true, 'PRIVATE_AUTHOR_IDENTITY'), (2, false, 'HIDDEN_AUTHOR')")
        for (id in 1..7) execute("insert into questions values ($id, 1, '자료 구조', '스택이란?', 'ko', true, null, 'graded', 'PRIVATE_ANSWER', 91, 'QUESTION', null)")
        execute("update questions set is_public=false where id=2")
        execute("update questions set deleted_at=current_timestamp where id=3")
        execute("update questions set status='grading' where id=4")
        execute("update questions set answer=null where id=5")
        execute("update questions set answer='   ' where id=6")
        execute("update questions set user_id=2 where id=7")
        execute("insert into question_search values (1,'en','Data structures','What is a stack?')")
        execute("insert into question_stats values (1,5)")
    }

    @Test
    fun `only public completed nondeleted opted-in questions can be shared`(): Unit = runBlocking {
        assertThat(adapter.findPreview(1, "en")?.question).isEqualTo("What is a stack?")
        for (id in 2L..8L) assertThat(adapter.findPreview(id, "en")).describedAs("question $id").isNull()
        execute("update questions set is_public=false where id=1")
        assertThat(adapter.findPreview(1, "en")).isNull()
    }

    @Test
    fun `localized preview falls back without leaking answers grades authors or incrementing views`(): Unit = runBlocking {
        val english = adapter.findPreview(1, "en")!!
        assertThat(english.topic).isEqualTo("Data structures")
        assertThat(english.contentLanguage).isEqualTo("en")
        val fallback = adapter.findPreview(1, "ja")!!
        assertThat(fallback.question).isEqualTo("스택이란?")
        assertThat(fallback.contentLanguage).isEqualTo("ko")
        assertThat(fallback.toString()).doesNotContain("PRIVATE_ANSWER", "PRIVATE_AUTHOR_IDENTITY", "91")
        repeat(3) { adapter.findPreview(1, "en") }
        val views = database.sql("select view_count from question_stats where question_id=1")
            .map { row, _ -> row.get("view_count", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        assertThat(views).isEqualTo(5)
    }

    @Test
    fun `voice preview uses canonical ID and source language and enforces extension ownership`(): Unit = runBlocking {
        execute("insert into voice_study_learning_records values (100, 1)")
        execute("update questions set record_type='VOICE_TUTOR', voice_record_id=100, status='completed' where id=1")
        // Unlike ordinary questions, voice search can contain source text in an English row.
        execute("update question_search set question='스택이란?' where question_id=1")
        val preview = adapter.findPreview(1, "en")!!
        assertThat(preview.id).isEqualTo(1)
        assertThat(preview.topic).isEqualTo("자료 구조")
        assertThat(preview.question).isEqualTo("스택이란?")
        assertThat(preview.contentLanguage).isEqualTo("ko")
        assertThat(adapter.findPreview(100, "en")).isNull()
        execute("update voice_study_learning_records set user_id=2 where id=100")
        assertThat(adapter.findPreview(1, "en")).isNull()
        execute("delete from voice_study_learning_records")
        assertThat(adapter.findPreview(1, "en")).isNull()
    }

    private suspend fun execute(sql: String) { database.sql(sql).fetch().rowsUpdated().awaitSingle() }
}
