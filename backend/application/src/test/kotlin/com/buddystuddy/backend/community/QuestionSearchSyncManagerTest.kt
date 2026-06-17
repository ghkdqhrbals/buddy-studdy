package com.buddystuddy.backend.community

import com.buddystuddy.account.domain.entity.UserEntity
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.community.application.port.outbound.QuestionSearchPort
import com.buddystuddy.backend.community.application.port.outbound.SearchResult
import com.buddystuddy.backend.community.application.service.QuestionSearchSyncManager
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.QuestionSearchTranslationPort
import com.buddystuddy.backend.study.application.port.outbound.TranslatedQuestionSearchText
import com.buddystuddy.community.domain.entity.QuestionSearchEntity
import com.buddystuddy.study.domain.entity.QuestionEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

class QuestionSearchSyncManagerTest {
    @Test
    fun `syncQuestion stores Korean and English search rows`() {
        val questions = FakeQuestionPort()
        val users = FakeUserPort(UserEntity(id = 7, displayName = "Min"))
        val search = FakeQuestionSearchPort()
        val translator = FakeTranslator()
        val manager = QuestionSearchSyncManager(questions, users, search, translator)

        manager.syncQuestion(
            QuestionEntity(
                id = 10,
                deviceId = "dev-1",
                userId = 7,
                topic = "분산 시스템",
                question = "장애 격리는 왜 필요한가요?",
                answer = "연쇄 장애를 막기 위해서입니다.",
                feedback = "좋습니다.",
                explanation = "격리는 장애 전파 범위를 줄입니다.",
                score = 90,
                language = "ko",
                scheduledFor = Instant.parse("2026-06-17T00:00:00Z"),
                createdAt = Instant.parse("2026-06-17T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-17T00:00:00Z"),
            )
        )

        assertThat(search.rows.map { it.language }).containsExactly("ko", "en")
        assertThat(search.rows.first { it.language == "ko" }.question).isEqualTo("장애 격리는 왜 필요한가요?")
        assertThat(search.rows.first { it.language == "en" }.question).isEqualTo("translated-en")
        assertThat(translator.calls).containsExactly("ko:en")
    }

    private class FakeTranslator : QuestionSearchTranslationPort {
        val calls = mutableListOf<String>()

        override fun translateSearchText(
            sourceLanguage: String,
            targetLanguage: String,
            topic: String,
            question: String,
            answer: String?,
            feedback: String?,
            explanation: String?,
        ): TranslatedQuestionSearchText {
            calls += "$sourceLanguage:$targetLanguage"
            return TranslatedQuestionSearchText(
                topic = "translated-$targetLanguage",
                question = "translated-$targetLanguage",
                answer = answer?.let { "translated-$targetLanguage" },
                feedback = feedback?.let { "translated-$targetLanguage" },
                explanation = explanation?.let { "translated-$targetLanguage" },
            )
        }
    }

    private class FakeQuestionSearchPort : QuestionSearchPort {
        val rows = mutableListOf<QuestionSearchEntity>()
        override fun save(entity: QuestionSearchEntity): QuestionSearchEntity {
            rows.removeAll { it.questionId == entity.questionId && it.language == entity.language }
            rows += entity
            return entity
        }

        override fun deleteByQuestionId(questionId: Long): Long = rows.removeAll { it.questionId == questionId }.let { if (it) 1 else 0 }
        override fun searchPublic(query: String?, limit: Int, offset: Int): SearchResult = SearchResult(emptyList(), 0)
    }

    private class FakeUserPort(private val user: UserEntity) : UserPort {
        override fun save(entity: UserEntity): UserEntity = entity
        override fun findById(id: Long): Optional<UserEntity> = Optional.ofNullable(user.takeIf { it.id == id })
        override fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> =
            if (user.id in ids.toSet()) mutableListOf(user) else mutableListOf()
        override fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionPort : QuestionPort {
        override fun save(entity: QuestionEntity): QuestionEntity = entity
        override fun findQuestionById(id: Long): Optional<QuestionEntity> = Optional.empty()
        override fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun countPendingForStudy(studyId: Long): Long = 0
        override fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> = emptyMap()
        override fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override fun findPublicAnsweredById(id: Long): QuestionEntity? = null
        override fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = emptyList()
        override fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
    }
}
