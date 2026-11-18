package com.buddystudy.backend

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.community.application.port.inbound.CommunityUseCase
import com.buddystudy.backend.study.application.model.QuestionChoiceCodec
import com.buddystudy.backend.study.application.model.QuestionChoiceOption
import com.buddystudy.backend.study.application.port.outbound.QuestionChoiceResponsePort
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.inbound.StudyRecordWriteUseCase
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionFormat
import com.buddystudy.study.domain.entity.QuestionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    "buddystudy.crypto.master-key=test-master-key", "buddystudy.auth.jwt-secret=test-jwt-secret",
])
class MultipleChoiceParticipationIntegrationTest : MySqlIntegrationTestSupport() {
    @Autowired lateinit var users: UserPort
    @Autowired lateinit var questions: QuestionPort
    @Autowired lateinit var responses: QuestionChoiceResponsePort
    @Autowired lateinit var community: CommunityUseCase
    @Autowired lateinit var database: DatabaseClient
    @Autowired lateinit var codec: QuestionChoiceCodec
    @Autowired lateinit var recordWriter: StudyRecordWriteUseCase

    @Test
    fun `mysql concurrent public submissions count owner and viewer exactly once without learning mutations`(): Unit = runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) {
        val owner = user()
        val viewer = user()
        val principal = Principal(viewer.id, "choice-${viewer.id}", 1, false)
        val question = question(owner.id)
        responses.insertOrRead(question.id, owner.id, "a", Instant.now())
        val beforeRecords = count("select count(*) from questions where user_id = ${viewer.id}")
        val beforeStats = count("select count(*) from user_stats where user_id = ${viewer.id}")
        val beforeQuota = count("select coalesce(sum(committed_count + reserved_count), 0) from user_quota where user_id = ${viewer.id}")

        val results = coroutineScope {
            List(12) { async(Dispatchers.Default) { community.votePublicQuestion(principal, question.id, "b") } }.awaitAll()
        }
        results.forEach { assertThat(it.multipleChoice?.selectedOptionId).isEqualTo("b") }
        val result = requireNotNull(community.getPublicQuestion(principal, question.id).multipleChoice)
        assertThat(result.totalParticipants).isEqualTo(2)
        assertThat(result.options.map { it.count }).containsExactly(1, 1)
        assertThat(result.options.map { it.percentage }).containsExactly(50.0, 50.0)
        assertThat(result.isCorrect).isFalse()
        assertThat(result.correctOptionId).isEqualTo("a")
        assertThat(result.explanation).isEqualTo("Private explanation")
        assertThat(count("select count(*) from question_choice_responses where question_id = ${question.id}")).isEqualTo(2)
        assertThat(count("select count(*) from questions where user_id = ${viewer.id}")).isEqualTo(beforeRecords)
        assertThat(count("select count(*) from user_stats where user_id = ${viewer.id}")).isEqualTo(beforeStats)
        assertThat(count("select coalesce(sum(committed_count + reserved_count), 0) from user_quota where user_id = ${viewer.id}")).isEqualTo(beforeQuota)
        val canonical = requireNotNull(questions.findQuestionById(question.id))
        assertThat(canonical.answer).isEqualTo("AOF")
        assertThat(canonical.score).isEqualTo(100)
        assertThatThrownBy { runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) { community.votePublicQuestion(principal, question.id, "a") } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.ANSWER_ALREADY_SUBMITTED)
    }

    @Test
    fun `mysql public reads hide objective answers until selection and enforce current visibility`(): Unit = runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) {
        val owner = user()
        val viewer = user()
        val principal = Principal(viewer.id, "choice-${viewer.id}", 1, false)
        val question = question(owner.id)
        responses.insertOrRead(question.id, owner.id, "a", Instant.now())
        listOf(null, principal).forEach { current ->
            val response = community.getPublicQuestion(current, question.id)
            assertThat(response.answer).isNull()
            assertThat(response.gradingResult).isNull()
            assertThat(response.localization?.answer).isNull()
            assertThat(response.localization?.aiResponse).isNull()
            assertThat(response.multipleChoice?.correctOptionId).isNull()
            assertThat(response.multipleChoice?.explanation).isNull()
            assertThat(response.multipleChoice?.totalParticipants).isNull()
            assertThat(response.multipleChoice?.options).allMatch { it.count == null && it.percentage == null }
        }
        assertVoteError(principal.copy(anonymous = true), question.id, ApiErrorCode.ACCOUNT_FORBIDDEN)
        assertThatThrownBy { runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) { community.votePublicQuestion(principal, question.id, "missing") } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        database.sql("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (:viewer, :owner, current_timestamp)")
            .bind("viewer", viewer.id).bind("owner", owner.id).fetch().rowsUpdated().awaitSingle()
        assertVoteError(principal, question.id, ApiErrorCode.RECORD_NOT_FOUND)
        database.sql("delete from user_blocks where blocker_user_id = :viewer").bind("viewer", viewer.id).fetch().rowsUpdated().awaitSingle()
        question.publicQuestion = false
        questions.save(question)
        assertVoteError(principal, question.id, ApiErrorCode.RECORD_NOT_FOUND)
        question.publicQuestion = true
        question.deletedAt = Instant.now()
        questions.save(question)
        assertVoteError(principal, question.id, ApiErrorCode.RECORD_NOT_FOUND)
        assertThat(responses.findSelections(listOf(question.id), viewer.id)).isEmpty()
    }

    @Test
    fun `mysql a concurrent vote cannot recreate participation after owner deletion`(): Unit = runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) {
        val owner = user()
        val viewer = user()
        val principal = Principal(viewer.id, "choice-${viewer.id}", 1, false)
        repeat(4) {
            val question = question(owner.id)
            responses.insertOrRead(question.id, owner.id, "a", Instant.now())
            coroutineScope {
                val vote = async(Dispatchers.Default) { runCatching { community.votePublicQuestion(principal, question.id, "b") } }
                val delete = async(Dispatchers.Default) { recordWriter.delete(owner.id, question.id, Instant.now()) }
                delete.await()
                vote.await().exceptionOrNull()?.let { error ->
                    assertThat(error).isInstanceOf(ApiException::class.java)
                    assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
                }
            }
            assertThat(responses.countByOptions(listOf(question.id))).isEmpty()
        }
    }

    @Test
    fun `mysql participation is removed with question or participant hard deletion`(): Unit = runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) {
        val owner = user()
        val viewer = user()
        val question = question(owner.id)
        responses.insertOrRead(question.id, owner.id, "a", Instant.now())
        responses.insertOrRead(question.id, viewer.id, "b", Instant.now())
        database.sql("delete from users where id = :id").bind("id", viewer.id).fetch().rowsUpdated().awaitSingle()
        assertThat(responses.countByOptions(listOf(question.id))[question.id]).isEqualTo(mapOf("a" to 1))
        database.sql("delete from questions where id = :id").bind("id", question.id).fetch().rowsUpdated().awaitSingle()
        assertThat(responses.countByOptions(listOf(question.id))).isEmpty()
    }

    private fun assertVoteError(principal: Principal, id: Long, code: ApiErrorCode) {
        assertThatThrownBy { runBlocking(com.buddystudy.backend.common.application.port.outbound.ClientQuestionContractContext(3)) { community.votePublicQuestion(principal, id, "a") } }
            .isInstanceOf(ApiException::class.java).extracting("code").isEqualTo(code)
    }

    private suspend fun count(sql: String): Long = database.sql(sql)
        .map { row, _ -> (row.get(0) as Number).toLong() }.one().awaitSingle()

    private suspend fun user(): UserEntity {
        val unique = UUID.randomUUID().toString()
        return users.save(UserEntity(provider = UserProvider.EMAIL, providerId = "choice-$unique",
            status = UserStatus.ACTIVE, displayName = "Choice $unique", email = "$unique@example.com", allowPublicQuestions = true))
    }

    private suspend fun question(userId: Long) = questions.save(QuestionEntity(
        deviceId = "choice-test-$userId", userId = userId, topic = "Redis", question = "Which persistence mode is append-only?",
        answer = "AOF", feedback = "Correct", explanation = "Private explanation", score = 100, correct = true,
        status = QuestionStatus.GRADED, sourceLanguage = SupportedLanguage.KOREAN,
        answerSourceLanguage = SupportedLanguage.KOREAN, aiResponseSourceLanguage = SupportedLanguage.KOREAN,
        publicQuestion = true, questionFormat = QuestionFormat.MULTIPLE_CHOICE,
        choiceOptionsJson = codec.encode(listOf(QuestionChoiceOption("a", "AOF"), QuestionChoiceOption("b", "RDB"))),
        correctOptionId = "a", selectedOptionId = "a", choiceExplanation = "Private explanation",
        answeredAt = Instant.now(), gradedAt = Instant.now(),
    ))
}
