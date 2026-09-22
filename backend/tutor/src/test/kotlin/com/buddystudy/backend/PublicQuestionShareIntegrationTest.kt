package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = [
    "buddystudy.scheduler.enabled=false", "buddystudy.streams.enabled=false",
    "buddystudy.crypto.master-key=test-master-key", "buddystudy.auth.jwt-secret=test-jwt-secret",
])
class PublicQuestionShareIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserPort
    @Autowired lateinit var questions: QuestionPort
    @Autowired lateinit var database: DatabaseClient
    @LocalServerPort var port: Int = 0
    private val http = HttpClient.newHttpClient()

    @Test
    fun `anonymous mysql share preview escapes content and never includes private answer or author fields`(): Unit = runBlocking {
        val author = author()
        val question = question(author.id)
        database.sql("insert into question_search (question_id, language, topic, question, updated_at) values (:id, 'en', 'English topic', :text, current_timestamp)")
            .bind("id", question.id).bind("text", "English <script>alert('x')</script> & question")
            .fetch().rowsUpdated().awaitSingle()
        val english = get("/questions/${question.id}?tl=en")
        assertThat(english.statusCode()).isEqualTo(200)
        assertThat(english.body()).contains("English topic", "English &lt;script&gt;", "lang=\"en\"", "buddystudy://public/questions/${question.id}")
            .doesNotContain("<script>", "PRIVATE ANSWER", "PRIVATE FEEDBACK", author.displayName, author.email)
        assertThat(english.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
        assertThat(english.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'")
        val fallback = get("/questions/${question.id}?tl=ja")
        assertThat(fallback.statusCode()).isEqualTo(200)
        assertThat(fallback.body()).contains("원본 질문", "lang=\"ko\"")
        val association = get("/.well-known/apple-app-site-association")
        assertThat(association.statusCode()).isEqualTo(200)
        assertThat(association.body()).contains("/questions/*", "/referrals/*")
    }

    @Test
    fun `mysql share checks current publication and returns identical missing body for every invisible state`(): Unit = runBlocking {
        val author = author()
        val visible = question(author.id)
        assertThat(get("/questions/${visible.id}").statusCode()).isEqualTo(200)
        val hidden = listOf(
            question(author.id, isPublic = false), question(author.id, deleted = true),
            question(author.id, status = QuestionStatus.UNGRADED), question(author.id, answer = "  "),
        )
        val missing = get("/questions/9223372036854775807")
        assertThat(missing.statusCode()).isEqualTo(404)
        hidden.forEach {
            val response = get("/questions/${it.id}")
            assertThat(response.statusCode()).isEqualTo(404)
            assertThat(response.body()).isEqualTo(missing.body())
        }
        visible.publicQuestion = false
        questions.save(visible)
        assertThat(get("/questions/${visible.id}").body()).isEqualTo(missing.body())
        visible.publicQuestion = true
        questions.save(visible)
        author.allowPublicQuestions = false
        users.save(author)
        val optOut = get("/questions/${visible.id}")
        assertThat(optOut.statusCode()).isEqualTo(404)
        assertThat(optOut.body()).isEqualTo(missing.body())
    }

    @Test
    fun `mysql canonical voice share uses source preview and excludes hidden empty foreign and deleted records`(): Unit = runBlocking {
        val author = author()
        val visible = publicFeedVoiceFixture(database, questions, author.id, "음성 주제", "음성 원문 질문")
        val hidden = listOf(
            publicFeedVoiceFixture(database, questions, author.id, "음성 주제", "Private", isPublic = false),
            publicFeedVoiceFixture(database, questions, author.id, "음성 주제", "Deleted", deleted = true),
            publicFeedVoiceFixture(database, questions, author.id, "음성 주제", "Blank answer", answer = " "),
            publicFeedVoiceFixture(database, questions, author.id, "음성 주제", " "),
        )
        // Projection intentionally has a row for every language even while translations are pending.
        val response = get("/questions/${visible.id}?tl=en")
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("음성 원문 질문", "lang=\"ko\"", "buddystudy://public/questions/${visible.id}")
            .doesNotContain("PRIVATE VOICE ANSWER", "question_turn_id", "source_hash", author.email)
        hidden.forEach { assertThat(get("/questions/${it.id}").statusCode()).isEqualTo(404) }
        val foreign = author()
        database.sql("update voice_study_learning_records set user_id=:owner where id=:id")
            .bind("owner", foreign.id).bind("id", visible.voiceRecordId!!).fetch().rowsUpdated().awaitSingle()
        assertThat(get("/questions/${visible.id}").statusCode()).isEqualTo(404)
        database.sql("delete from voice_study_learning_records where id=:id")
            .bind("id", visible.voiceRecordId!!).fetch().rowsUpdated().awaitSingle()
        assertThat(get("/questions/${visible.id}").statusCode()).isEqualTo(404)
    }

    private suspend fun author(): UserEntity {
        val unique = UUID.randomUUID().toString()
        return users.save(UserEntity(provider = UserProvider.EMAIL, providerId = "share-$unique",
            status = UserStatus.ACTIVE, displayName = "Private-author-$unique", email = "$unique@example.com"))
    }

    private suspend fun question(
        userId: Long, isPublic: Boolean = true, deleted: Boolean = false,
        status: QuestionStatus = QuestionStatus.GRADED, answer: String = "PRIVATE ANSWER",
    ) = questions.save(QuestionEntity(
        deviceId = "share-test-$userId", userId = userId, topic = "원본 주제", question = "원본 질문",
        answer = answer, feedback = "PRIVATE FEEDBACK", score = 90, status = status,
        sourceLanguage = SupportedLanguage.KOREAN, answerSourceLanguage = SupportedLanguage.KOREAN,
        publicQuestion = isPublic, deletedAt = if (deleted) Instant.now() else null,
    ))

    private fun get(path: String) = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
