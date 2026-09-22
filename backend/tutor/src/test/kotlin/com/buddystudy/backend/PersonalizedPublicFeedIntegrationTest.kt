package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.community.application.model.PublicFeedScope
import com.buddystudy.backend.community.application.model.PublicFeedSort
import com.buddystudy.backend.community.application.port.inbound.TopicSubscriptionUseCase
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest
@TestPropertySource(properties = [
    "buddystudy.scheduler.enabled=false", "buddystudy.streams.enabled=false",
    "buddystudy.analytics.datasource.database-name=", "buddystudy.crypto.master-key=test-master-key",
    "buddystudy.auth.jwt-secret=test-jwt-secret",
])
class PersonalizedPublicFeedIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserPort
    @Autowired lateinit var questions: QuestionPort
    @Autowired lateinit var subscriptions: TopicSubscriptionUseCase
    @Autowired lateinit var database: DatabaseClient

    @Test
    fun `mysql applies subscription ranking and localized visibility before exact pages`(): Unit = runBlocking {
        val unique = UUID.randomUUID().toString()
        val viewer = user("viewer-$unique")
        val author = user("author-$unique")
        val blocked = user("blocked-$unique")
        val hidden = user("hidden-$unique", false)
        val principal = Principal(viewer.id, "device-$unique", 1, false)
        val topic = "Data\u2003Structures $unique"
        subscriptions.replace(principal, listOf(topic))
        val a = question(author.id, "DATA_structures-$unique", unique)
        val b = question(author.id, "다른 주제", unique)
        val missingCounters = question(author.id, "data structures $unique", unique)
        val global = question(author.id, "Discovery", unique)
        val excluded = listOf(
            question(author.id, topic, unique, isPublic = false),
            question(author.id, topic, unique, deleted = true),
            question(author.id, topic, unique, status = QuestionStatus.UNGRADED),
            question(author.id, topic, unique, answer = "  "),
            question(blocked.id, topic, unique),
            question(hidden.id, topic, unique),
        )
        database.sql("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (:viewer, :blocked, current_timestamp)")
            .bind("viewer", viewer.id).bind("blocked", blocked.id).fetch().rowsUpdated().awaitSingle()
        // The translated topic can be selected in the English app while the source remains Korean.
        database.sql("insert into question_search (question_id, language, topic, question, updated_at) values (:id, 'en', :topic, :query, current_timestamp)")
            .bind("id", b.id).bind("topic", "Data\u00a0Structures $unique").bind("query", "English $unique")
            .fetch().rowsUpdated().awaitSingle()
        stats(a.id, 100, 1)
        stats(b.id, 10, 50)
        stats(global.id, 1_000_000, 1_000_000)
        excluded.forEach { stats(it.id, 2_000_000, 2_000_000) }

        suspend fun feed(offset: Int, scope: PublicFeedScope = PublicFeedScope.ALL, language: String = "ko") =
            questions.findPersonalizedPublicAnswered(viewer.id, unique, language, PublicFeedSort.RECOMMENDED, scope, 2, offset)
        assertThat(feed(0).totalElements).isEqualTo(4)
        assertThat(feed(0).content.map { it.id }).containsExactly(b.id, a.id)
        assertThat(feed(1).content.map { it.id }).containsExactly(a.id, missingCounters.id)
        assertThat(feed(2).content.map { it.id }).containsExactly(missingCounters.id, global.id)
        assertThat(feed(0, PublicFeedScope.FOLLOWING).totalElements).isEqualTo(3)
        assertThat(feed(0, PublicFeedScope.FOLLOWING, "en").content.map { it.id }).containsExactly(b.id)
        subscriptions.replace(principal, emptyList())
        assertThat(feed(0, PublicFeedScope.FOLLOWING).totalElements).isZero()
        assertThat(feed(0).content.first().id).isEqualTo(global.id)
    }

    @Test
    fun `mysql ranked following includes canonical voice and excludes hidden blocked and foreign extensions`(): Unit = runBlocking {
        val unique = UUID.randomUUID().toString()
        val viewer = user("voice-viewer-$unique")
        val author = user("voice-author-$unique")
        val blocked = user("voice-blocked-$unique")
        val topic = "Voice $unique"
        subscriptions.replace(Principal(viewer.id, "voice-$unique", 1, false), listOf(topic))
        val visible = publicFeedVoiceFixture(database, questions, author.id, topic, "Voice $unique")
        publicFeedVoiceFixture(database, questions, author.id, topic, "Private $unique", isPublic = false)
        publicFeedVoiceFixture(database, questions, author.id, topic, "Deleted $unique", deleted = true)
        publicFeedVoiceFixture(database, questions, author.id, topic, "Empty $unique", answer = " ")
        publicFeedVoiceFixture(database, questions, blocked.id, topic, "Blocked $unique")
        val foreign = publicFeedVoiceFixture(database, questions, author.id, topic, "Foreign $unique")
        database.sql("update voice_study_learning_records set user_id=:owner where id=:id")
            .bind("owner", blocked.id).bind("id", foreign.voiceRecordId!!).fetch().rowsUpdated().awaitSingle()
        database.sql("insert into user_blocks (blocker_user_id,blocked_user_id,created_at) values (:viewer,:blocked,current_timestamp)")
            .bind("viewer", viewer.id).bind("blocked", blocked.id).fetch().rowsUpdated().awaitSingle()
        suspend fun page(offset: Int) = questions.findPersonalizedPublicAnswered(
            viewer.id, unique, "en", PublicFeedSort.RECOMMENDED, PublicFeedScope.FOLLOWING, 1, offset,
        )
        assertThat(page(0).totalElements).isEqualTo(1)
        assertThat(page(0).content.map { it.id }).containsExactly(visible.id)
        assertThat(page(1).content).isEmpty()
        database.sql("delete from voice_study_learning_records where id=:id")
            .bind("id", visible.voiceRecordId!!).fetch().rowsUpdated().awaitSingle()
        assertThat(page(0).totalElements).isZero()
    }

    private suspend fun user(name: String, isPublic: Boolean = true) = users.save(UserEntity(
        provider = UserProvider.EMAIL, providerId = name, status = UserStatus.ACTIVE,
        displayName = name, email = "$name@example.com", allowPublicQuestions = isPublic,
    ))

    private suspend fun question(
        userId: Long, topic: String, query: String, isPublic: Boolean = true,
        deleted: Boolean = false, status: QuestionStatus = QuestionStatus.GRADED, answer: String = "Answer",
    ) = questions.save(QuestionEntity(
        deviceId = "feed-test-$userId", userId = userId, question = "Question $query", topic = topic,
        status = status, answer = answer, score = 90, publicQuestion = isPublic,
        sourceLanguage = SupportedLanguage.KOREAN, answerSourceLanguage = SupportedLanguage.KOREAN,
        gradedAt = Instant.now(), deletedAt = if (deleted) Instant.now() else null,
    ))

    private suspend fun stats(id: Long, views: Int, likes: Int) {
        database.sql("insert into question_stats (question_id, view_count, like_count, comment_count, updated_at) values (:id, :views, :likes, 0, current_timestamp)")
            .bind("id", id).bind("views", views).bind("likes", likes).fetch().rowsUpdated().awaitSingle()
    }
}
