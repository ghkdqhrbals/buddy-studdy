package com.buddystudy.backend.study

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.service.QuestionNotificationRecoveryService
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuestionNotificationRecoveryServiceTest {
    @Test
    fun `reconstructs an empty native question notification payload from its event id`(): Unit = runBlocking {
        val question = QuestionEntity(
            id = 45,
            userId = 7,
            studyId = 12,
            deviceId = "device-7",
            question = "**Redis Stream**의 consumer group을 설명하세요.",
            topic = "Redis",
            difficultyLevel = 4,
        )
        val study = StudyEntity(
            id = 12,
            userId = 7,
            topic = "Redis",
            notificationSound = "ping.aiff",
            intervalMinutes = 30,
        )
        val service = QuestionNotificationRecoveryService(
            questions = QuestionPortFixture(question),
            studies = StudyPortFixture(study),
            users = UserPortFixture(
                UserEntity(id = 7, providerId = "user-7", status = "ACTIVE", appLanguage = "ja-JP"),
            ),
        )

        val recovered = service.recover("question-created-45")

        assertThat(recovered).isNotNull
        assertThat(recovered?.eventId).isEqualTo("question-created-45")
        assertThat(recovered?.userId).isEqualTo(7)
        assertThat(recovered?.type).isEqualTo("STUDY_QUESTION")
        assertThat(recovered?.title).isNotBlank()
        assertThat(recovered?.body).isEqualTo("Redis Stream의 consumer group을 설명하세요.")
        assertThat(recovered?.threadType).isEqualTo("study_question")
        assertThat(recovered?.threadId).isEqualTo("45")
        assertThat(recovered?.deepLink).isEqualTo("buddystudy://records/45")
        assertThat(recovered?.metadataJson).contains("\"language\":\"ja\"")
        assertThat(recovered?.shouldPush).isTrue()
    }

    @Test
    fun `does not reinterpret unrelated notification event ids`(): Unit = runBlocking {
        val service = QuestionNotificationRecoveryService(
            questions = QuestionPortFixture(null),
            studies = StudyPortFixture(null),
            users = UserPortFixture(null),
        )

        assertThat(service.recover("comment-created-45")).isNull()
    }

    private class QuestionPortFixture(
        private val question: QuestionEntity?,
    ) : QuestionPort by unsupportedPort() {
        override suspend fun findQuestionById(id: Long): QuestionEntity? = question?.takeIf { it.id == id }
    }

    private class StudyPortFixture(
        private val study: StudyEntity?,
    ) : StudyPort by unsupportedPort() {
        override suspend fun findByIdAndUserId(id: Long, userId: Long): StudyEntity? =
            study?.takeIf { it.id == id && it.userId == userId }
    }

    private class UserPortFixture(
        private val user: UserEntity?,
    ) : UserPort by unsupportedPort() {
        override suspend fun findById(id: Long): UserEntity? = user?.takeIf { it.id == id }
    }

    private companion object {
        inline fun <reified T> unsupportedPort(): T =
            java.lang.reflect.Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ ->
                error("Unexpected ${T::class.simpleName} call: ${method.name}")
            } as T
    }
}
