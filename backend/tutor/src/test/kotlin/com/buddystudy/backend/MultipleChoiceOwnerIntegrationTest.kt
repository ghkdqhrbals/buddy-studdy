package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import com.buddystudy.backend.study.application.port.inbound.AnswerGradingWriteUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionChoiceResponsePort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionFormat
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest
@TestPropertySource(properties = [
    "buddystudy.scheduler.enabled=false", "buddystudy.streams.enabled=false",
    "buddystudy.analytics.datasource.database-name=", "buddystudy.crypto.master-key=test-master-key",
    "buddystudy.auth.jwt-secret=test-jwt-secret",
])
class MultipleChoiceOwnerIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserPort
    @Autowired lateinit var questions: QuestionPort
    @Autowired lateinit var responses: QuestionChoiceResponsePort
    @Autowired lateinit var writer: AnswerGradingWriteUseCase
    @Autowired lateinit var studies: com.buddystudy.backend.study.application.port.outbound.StudyPort
    @Autowired lateinit var settings: com.buddystudy.backend.settings.application.port.inbound.SettingsUseCase

    @Test
    fun `saved root settings preference survives reads and child edits without explicit field`() = runBlocking<Unit> {
        val unique = UUID.randomUUID().toString()
        val user = users.save(UserEntity(provider = UserProvider.EMAIL, providerId = unique,
            email = "$unique@example.com", displayName = "settings-${unique.take(10)}", status = UserStatus.ACTIVE))
        val principal = com.buddystudy.backend.auth.Principal(user.id, "settings-$unique", 1, false)
        val root = studies.save(com.buddystudy.study.domain.entity.StudyEntity(userId = user.id, deviceId = principal.deviceId, topic = "Root $unique"))
        val child = studies.save(com.buddystudy.study.domain.entity.StudyEntity(userId = user.id, deviceId = principal.deviceId, parentStudyId = root.id, topic = "Child $unique"))
        assertThat(root.questionFormatPreference).isEqualTo(com.buddystudy.study.domain.entity.QuestionFormatPreference.MIXED)
        settings.upsertStudySettings(principal, root.id, com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand(
            questionFormatPreference = com.buddystudy.study.domain.entity.QuestionFormatPreference.MULTIPLE_CHOICE))
        settings.upsertStudySettings(principal, root.id, com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand())
        settings.upsertStudySettings(principal, child.id, com.buddystudy.backend.settings.application.port.inbound.ScheduleCommand(
            questionFormatPreference = com.buddystudy.study.domain.entity.QuestionFormatPreference.FREE_TEXT))
        assertThat(studies.findByIdAndUserId(root.id, user.id)?.questionFormatPreference).isEqualTo(com.buddystudy.study.domain.entity.QuestionFormatPreference.MULTIPLE_CHOICE)
        assertThat(settings.studySettings(principal, child.id).questionFormatPreference).isEqualTo(com.buddystudy.study.domain.entity.QuestionFormatPreference.MULTIPLE_CHOICE)
    }

    @Test
    fun `owner selection and accepted grading commit once and cannot change`() = runBlocking<Unit> {
        val unique = UUID.randomUUID().toString()
        val user = users.save(UserEntity(provider = UserProvider.EMAIL, providerId = unique,
            email = "$unique@example.com", displayName = "mc-${unique.take(12)}", status = UserStatus.ACTIVE))
        val question = questions.save(QuestionEntity(deviceId = "owner-$unique", userId = user.id,
            question = "Which number is even?", topic = "Math", source = QuestionSource.MANUAL,
            questionFormat = QuestionFormat.MULTIPLE_CHOICE,
            choiceOptionsJson = QuestionChoiceCodec().encode(listOf(QuestionChoiceOption("A", "2"), QuestionChoiceOption("B", "3"))),
            correctOptionId = "A", choiceExplanation = "2 is divisible by 2."))
        val now = Instant.now()
        val invalid = runCatching { writer.queueChoice(user.id, question.id, "Z", "en", now) }.exceptionOrNull()
        assertThat(invalid).isInstanceOf(ApiException::class.java)
        assertThat(responses.findSelections(listOf(question.id), user.id)).isEmpty()
        assertThat(questions.findQuestionById(question.id)?.status).isEqualTo(QuestionStatus.UNGRADED)
        val first = writer.queueChoice(user.id, question.id, "A", "en", now)
        val replay = writer.queueChoice(user.id, question.id, "A", "en", now.plusSeconds(1))
        assertThat(first.question.status).isEqualTo(QuestionStatus.GRADING)
        assertThat(first.question.answer).isEqualTo("2")
        assertThat(replay.question.gradingRequestId).isEqualTo(first.question.gradingRequestId)
        assertThat(replay.outboxes).isEmpty()
        assertThat(responses.countByOptions(listOf(question.id))[question.id]).isEqualTo(mapOf("A" to 1))
        val changed = runCatching { writer.queueChoice(user.id, question.id, "B", "en", now.plusSeconds(2)) }.exceptionOrNull()
        assertThat(changed).isInstanceOf(ApiException::class.java)
        assertThat(questions.findQuestionById(question.id)?.selectedOptionId).isEqualTo("A")
        assertThat(responses.findSelections(listOf(question.id), user.id)[question.id]).isEqualTo("A")
    }
}
