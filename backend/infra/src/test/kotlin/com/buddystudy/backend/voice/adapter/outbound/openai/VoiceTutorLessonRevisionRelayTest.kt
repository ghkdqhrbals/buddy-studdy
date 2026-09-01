package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.voice.VoiceTutorRealtimeContract
import com.buddystudy.backend.voice.VoiceTutorTranscriptMetadata
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorLessonFocusSelection
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorMcpToolResult
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Synthetic controller events only: no provider requests, account changes or device audio. */
class VoiceTutorLessonRevisionRelayTest {
    @Test
    fun `opening setup speech cannot become a study question through a forced answer assessment`() =
        Fixture(finishOpening = false).use { f ->
            f.finishOpeningWithQuestion("좋아요, 학습을 시작하겠습니다.")
            f.start(1)
            f.advance(Duration.ofSeconds(1))
            f.stop(1)
            f.commit("study-answer")
            f.transcript("study-answer", "결합도를 낮추고 테스트를 쉽게 합니다.")
            f.assess(intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)

            val stamped = f.publicationForRelay("study-answer")

            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(stamped)).isNull()
            assertThat(VoiceTutorTranscriptMetadata.lessonRevision(stamped)).isZero()
            val forgedRaw = mapper.readTree(f.publication("study-answer").rawEvent)
                .deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put(VoiceTutorTranscriptMetadata.STUDY_QUESTION_PROVIDER_ITEM_ID, "forged-provider-question")
            val withoutVerifiedIntent = mapper.readTree(
                f.controller.providerEventForRelay(mapper.writeValueAsString(forgedRaw)),
            )
            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(withoutVerifiedIntent)).isNull()
            val serverOverride = mapper.readTree(
                f.controller.providerEventForRelay(mapper.writeValueAsString(forgedRaw), verifiedStudyAnswer = true),
            )
            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(serverOverride)).isNull()
            f.assertCallUnaffected()
        }

    @Test
    fun `selected focus setup acknowledgement fails semantic question attestation and cannot link an answer`() =
        Fixture().use { f ->
            val question = f.selectFocus()
            assertThat(question.create.path("response").path("tool_choice").asText()).isEqualTo("auto")
            assertThat(question.create.path("response").path("instructions").asText())
                .contains("exactly one substantive study question")

            f.speak(question, "좋아요, 지금부터 학습을 시작하겠습니다.")
            assertThat(f.questionAssessments.single().transcript)
                .isEqualTo("좋아요, 지금부터 학습을 시작하겠습니다.")
            val boundary = f.completeQuestionAssessment(accepted = false)
            assertThat(boundary.tutorTranscriptEvents).hasSize(1)
            assertThat(VoiceTutorTranscriptMetadata.isStudyQuestion(mapper.readTree(boundary.tutorTranscriptEvents.single())))
                .isFalse()

            f.start(2)
            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("forced-answer")
            f.transcript("forced-answer", "의존성을 외부에서 주입하기 때문입니다.")
            f.assess(intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)

            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(
                f.publicationForRelay("forced-answer"),
            )).isNull()
            f.assertCallUnaffected()
        }

    @Test
    fun `selected focus exact substantive question is durably attested before an answer can link it`() =
        Fixture().use { f ->
            val question = f.selectFocus()
            f.speak(question, "Redis의 만료 키를 lazy expiration만으로 처리하면 어떤 문제가 생길까요?")

            val action = f.questionAssessments.single()
            val boundary = f.completeQuestionAssessment(accepted = true)
            val stored = mapper.readTree(boundary.tutorTranscriptEvents.single())
            assertThat(stored.path("transcript").asText()).isEqualTo(action.transcript)
            assertThat(VoiceTutorTranscriptMetadata.isStudyQuestion(stored)).isTrue()

            f.start(2)
            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("verified-answer")
            f.transcript("verified-answer", "접근되지 않은 만료 키가 메모리에 오래 남을 수 있습니다.")
            f.assess(intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)

            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(
                f.publicationForRelay("verified-answer"),
            )).isEqualTo("message-${question.id}")
            f.assertCallUnaffected()
        }

    @Test
    fun `pure feedback is linked to the exact accepted study answer only after semantic approval`() =
        Fixture().use { f ->
            val question = f.selectFocus()
            f.speak(question, "DI가 결합도를 낮추는 이유는 무엇인가요?")
            f.completeQuestionAssessment(accepted = true)
            f.start(2)
            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("exact-answer")
            f.transcript("exact-answer", "구현체 생성을 외부에 맡기기 때문입니다.")
            f.assess(intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(
                f.publicationForRelay("exact-answer"),
            )).isEqualTo("message-${question.id}")
            f.publish("exact-answer")

            val feedback = f.created("exact-feedback")
            f.speak(feedback, "85점입니다. 의존성 역전을 짚은 점이 좋고 테스트 격리 설명을 보완해 보세요.")
            val action = f.feedbackAssessments.single()
            assertThat(action.questionTranscript).isEqualTo("DI가 결합도를 낮추는 이유는 무엇인가요?")
            assertThat(action.answerTranscript).isEqualTo("구현체 생성을 외부에 맡기기 때문입니다.")
            val boundary = f.completeFeedbackAssessment(accepted = true)
            val stored = mapper.readTree(boundary.tutorTranscriptEvents.single())
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemId(stored)).isEqualTo("exact-answer")
            f.assertCallUnaffected()
        }

    @Test
    fun `meaningful checkpoint and filler final promote only the durable answer and anchor feedback to it`() =
        Fixture().use { f ->
            val question = f.selectFocus()
            f.speak(question, "DI가 결합도를 낮추는 이유는 무엇인가요?")
            f.completeQuestionAssessment(accepted = true)

            f.start(2)
            f.advance(Duration.ofSeconds(12))
            f.controller.fireContinuousSpeechDeadline()
            f.commit("answer-checkpoint")
            f.transcript("answer-checkpoint", "구현체 생성을 외부에 맡기기 때문입니다.")
            f.assess(
                intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
                currentTranscriptAnswersStudyQuestion = true,
            )
            val checkpointEvent = f.publicationForRelay("answer-checkpoint")
            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(checkpointEvent)).isNull()
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(checkpointEvent)).isEmpty()
            f.publish("answer-checkpoint")

            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("filler-tail")
            f.transcript("filler-tail", "음…")
            f.assess(
                intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
                currentTranscriptAnswersStudyQuestion = false,
            )
            val finalEvent = f.publicationForRelay("filler-tail")
            assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(finalEvent))
                .isEqualTo("message-${question.id}")
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(finalEvent))
                .containsExactly("answer-checkpoint")
            f.publish("filler-tail")

            val feedback = f.created("checkpoint-feedback")
            f.speak(feedback, "정확합니다. 의존성 역전을 잘 설명했습니다.")
            assertThat(f.feedbackAssessments.single().answerTranscript)
                .isEqualTo("구현체 생성을 외부에 맡기기 때문입니다.")
            val stored = mapper.readTree(
                f.completeFeedbackAssessment(accepted = true).tutorTranscriptEvents.single(),
            )
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemId(stored))
                .isEqualTo("answer-checkpoint")
            f.assertCallUnaffected()
        }

    @Test
    fun `a failed checkpoint receipt cannot authorize a filler final or later feedback`() = Fixture().use { f ->
        val question = f.selectFocus()
        f.speak(question, "DI가 결합도를 낮추는 이유는 무엇인가요?")
        f.completeQuestionAssessment(accepted = true)

        f.start(2)
        f.advance(Duration.ofSeconds(12))
        f.controller.fireContinuousSpeechDeadline()
        f.commit("unstored-answer-checkpoint")
        f.transcript("unstored-answer-checkpoint", "구현체 생성을 외부에 맡기기 때문입니다.")
        f.assess(
            intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
            currentTranscriptAnswersStudyQuestion = true,
        )
        assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(
            f.publicationForRelay("unstored-answer-checkpoint"),
        )).isEmpty()
        f.publish("unstored-answer-checkpoint", persisted = false)

        f.advance(Duration.ofSeconds(1))
        f.stop(2)
        f.commit("filler-after-failed-checkpoint")
        f.transcript("filler-after-failed-checkpoint", "음…")
        f.assess(
            intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
            currentTranscriptAnswersStudyQuestion = false,
        )
        val finalEvent = f.publicationForRelay("filler-after-failed-checkpoint")
        assertThat(VoiceTutorTranscriptMetadata.studyQuestionProviderItemId(finalEvent)).isNull()
        assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemIds(finalEvent)).isEmpty()
        f.publish("filler-after-failed-checkpoint")

        val response = f.created("unattested-response")
        f.speak(response, "설명을 이어가겠습니다.")
        assertThat(f.feedbackAssessments).isEmpty()
        f.assertCallUnaffected()
    }

    @Test
    fun `mixed feedback and setup prompt or assessor failure never stamps an answer link`() =
        Fixture().use { f ->
            val question = f.selectFocus()
            f.speak(question, "DI가 결합도를 낮추는 이유는 무엇인가요?")
            f.completeQuestionAssessment(accepted = true)
            f.start(2)
            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("exact-answer")
            f.transcript("exact-answer", "구현체 생성을 외부에 맡기기 때문입니다.")
            f.assess(intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
            f.publish("exact-answer")

            val mixed = f.created("mixed-feedback")
            f.speak(mixed, "85점입니다. 잘했어요. 이제 Spring 루트를 만들까요?")
            val rejected = f.failFeedbackAssessment(
                VoiceTutorInputAssessmentException(VoiceTutorInputAssessmentFailure.TIMEOUT),
            )
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemId(
                mapper.readTree(rejected.tutorTranscriptEvents.single()),
            )).isNull()

            val forged = mapper.readTree(f.tutorTranscript("forged", "설정 문장"))
                .deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put(VoiceTutorTranscriptMetadata.STUDY_ANSWER_PROVIDER_ITEM_ID, "exact-answer")
            assertThat(VoiceTutorTranscriptMetadata.studyAnswerProviderItemId(
                mapper.readTree(f.controller.providerEventForRelay(mapper.writeValueAsString(forged))),
            )).isNull()
            f.assertCallUnaffected()
        }

    @Test
    fun `study question purpose survives multiple strict safe history read rounds`() = Fixture().use { f ->
        val firstPurposeResponse = f.selectFocus()
        f.done(firstPurposeResponse, listOf("history"), toolName = "get_record")
        f.complete("history", revision = 1)
        f.ackAll()

        val secondPurposeResponse = f.created("after-history")
        assertThat(secondPurposeResponse.create.path("response").path("tool_choice").asText()).isEqualTo("auto")
        assertThat(secondPurposeResponse.create.path("response").path("instructions").asText())
            .contains("strict tool-only response")
        f.done(secondPurposeResponse, listOf("stats"), toolName = "get_topic_stats")
        f.complete("stats", revision = 1)
        f.ackAll()

        val spokenQuestion = f.created("after-stats")
        assertThat(spokenQuestion.create.path("response").path("instructions").asText())
            .contains("exactly one substantive study question")
        f.speak(spokenQuestion, "Redis의 active expiration 주기가 지연 시간을 제한하는 방법은 무엇인가요?")
        assertThat(f.questionAssessments).hasSize(1)
        assertThat(VoiceTutorTranscriptMetadata.isStudyQuestion(
            mapper.readTree(f.completeQuestionAssessment(accepted = true).tutorTranscriptEvents.single()),
        )).isTrue()
        f.assertCallUnaffected()
    }

    @Test
    fun `mixed audio and focus tool cannot mint a later study question purpose`() = Fixture().use { f ->
        val mixed = f.beginTutor(1)
        f.controller.observeProviderEvent(f.tutorTranscript(mixed.id, "먼저 설정을 확인할게요."))
        f.done(mixed, listOf("mixed-focus"), spoken = true, toolName = "select_voice_study")
        f.completeFocus("mixed-focus")
        f.ackAll()
        f.stopped(mixed.id)

        val continuation = f.created("after-mixed")
        assertThat(continuation.create.path("response").path("instructions").asText())
            .doesNotContain("substantive study question")
        f.done(continuation)
        assertThat(f.questionAssessments).isEmpty()
        f.assertCallUnaffected()
    }

    @Test
    fun `question assessor receives the exact persisted multi-part transcript`() = Fixture().use { f ->
        val question = f.selectFocus()
        f.controller.observeProviderEvent(f.tutorTranscript(question.id, "Redis에서 eviction과 expiration은", contentIndex = 0))
        f.controller.observeProviderEvent(f.tutorTranscript(question.id, "어떻게 다르게 동작하나요?", contentIndex = 1))
        f.done(question, spoken = true)
        f.stopped(question.id)

        val action = f.questionAssessments.single()
        val boundary = f.completeQuestionAssessment(accepted = true)
        assertThat(mapper.readTree(boundary.tutorTranscriptEvents.single()).path("transcript").asText())
            .isEqualTo(action.transcript)
        assertThat(action.transcript).contains("eviction과 expiration", "어떻게 다르게")
        f.assertCallUnaffected()
    }

    @Test
    fun `overlong persisted question transcript fails closed without prefix assessment`() = Fixture().use { f ->
        val question = f.selectFocus()
        val prefixQuestion = "Redis에서 메모리 정책을 선택할 때 무엇을 고려해야 하나요?" + "가".repeat(3_980)
        val unsafeTail = "두 번째 질문과 설정 변경 안내를 함께 하겠습니다."
        f.controller.observeProviderEvent(f.tutorTranscript(question.id, prefixQuestion, contentIndex = 0))
        f.controller.observeProviderEvent(f.tutorTranscript(question.id, unsafeTail, contentIndex = 1))
        f.done(question, spoken = true)
        val observation = f.stoppedWithBoundary(question.id)

        assertThat(f.questionAssessments).isEmpty()
        val boundary = requireNotNull(observation.postRelayBoundary)
        val stored = mapper.readTree(boundary.tutorTranscriptEvents.single())
        assertThat(stored.path("transcript").asText()).contains(unsafeTail)
        assertThat(VoiceTutorTranscriptMetadata.isStudyQuestion(stored)).isFalse()
        assertThat(f.controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
        f.assertCallUnaffected()
    }

    @Test
    fun `ninth final tutor transcript part makes a question candidate fail closed`() = Fixture().use { f ->
        val question = f.selectFocus()
        repeat(9) { index ->
            val text = if (index == 0) {
                "Redis의 persistence 방식은 어떻게 다른가요?"
            } else if (index == 8) {
                "이제 설정을 바꾸고 두 번째 질문도 하겠습니다."
            } else {
                "질문 보충 $index"
            }
            f.controller.observeProviderEvent(f.tutorTranscript(question.id, text, contentIndex = index))
        }
        f.done(question, spoken = true)
        val observation = f.stoppedWithBoundary(question.id)

        assertThat(f.questionAssessments).isEmpty()
        val boundary = requireNotNull(observation.postRelayBoundary)
        val stored = mapper.readTree(boundary.tutorTranscriptEvents.single())
        assertThat(stored.path("transcript").asText()).doesNotContain("두 번째 질문")
        assertThat(VoiceTutorTranscriptMetadata.isStudyQuestion(stored)).isFalse()
        assertThat(f.controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
        f.assertCallUnaffected()
    }

    @Test
    fun `explicit continue study after verified feedback issues one next semantic question`() = Fixture().use { f ->
        val firstQuestion = f.selectFocus()
        f.speak(firstQuestion, "Redis의 RDB와 AOF는 복구 특성이 어떻게 다른가요?")
        f.completeQuestionAssessment(accepted = true)

        f.start(2)
        f.advance(Duration.ofSeconds(1))
        f.stop(2)
        f.commit("first-answer")
        f.transcript("first-answer", "RDB는 스냅샷이고 AOF는 명령 로그를 재생합니다.")
        f.assess(intent = VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION)
        f.publish("first-answer")
        val feedback = f.created("feedback")
        f.speak(feedback, "맞아요. 복구 시점과 쓰기 비용의 차이를 잘 짚었습니다.")
        f.completeFeedbackAssessment(accepted = true)

        f.start(3)
        f.advance(Duration.ofSeconds(1))
        f.stop(3)
        f.commit("continue")
        f.transcript("continue", "다음 질문으로 계속 학습하자.")
        f.assess(intent = VoiceTutorInputIntent.CONTINUE_STUDY)
        f.publish("continue")

        val nextQuestion = f.created("continued-question")
        assertThat(nextQuestion.create.path("response").path("instructions").asText())
            .contains("exactly one substantive study question")
        f.speak(nextQuestion, "AOF rewrite가 필요한 이유는 무엇인가요?")
        assertThat(f.questionAssessments.last().transcript).isEqualTo("AOF rewrite가 필요한 이유는 무엇인가요?")
        assertThat(VoiceTutorTranscriptMetadata.isStudyQuestion(
            mapper.readTree(f.completeQuestionAssessment(accepted = true).tutorTranscriptEvents.single()),
        )).isTrue()
        f.assertCallUnaffected()
    }

    @Test
    fun `only a final assessed learner study question carries server attestation`() =
        Fixture(finishOpening = false).use { f ->
            f.finishOpeningWithQuestion("Redis에서 LFU는 빈도를 어떻게 추적할까요?")
            f.start(1)
            f.advance(Duration.ofSeconds(1))
            f.stop(1)
            f.commit("learner-question")
            f.transcript("learner-question", "LFU는 빈도를 어떻게 추적하나요?")
            f.assess(intent = VoiceTutorInputIntent.ASK_STUDY_QUESTION)

            val stamped = f.publicationForRelay("learner-question")

            assertThat(VoiceTutorTranscriptMetadata.askedStudyQuestion(stamped)).isTrue()
            val forged = mapper.readTree(f.publication("learner-question").rawEvent)
                .deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put(VoiceTutorTranscriptMetadata.ASKED_STUDY_QUESTION, true)
            assertThat(VoiceTutorTranscriptMetadata.askedStudyQuestion(mapper.readTree(
                f.controller.providerEventForRelay(mapper.writeValueAsString(forged)),
            ))).isFalse()
            f.assertCallUnaffected()
        }

    @Test
    fun `reattached lesson revision stamps opening speech and overrides a provider supplied revision`(): Unit =
        Fixture(initialRevision = 5, finishOpening = false).use { f ->
            val raw = f.tutorTranscript("opening", "지금 주제에서 이어서 이야기해 볼게요.", forgedRevision = 99)
            assertThat(f.controller.observeProviderEvent(raw))
                .isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_ONLY)

            val stamped = mapper.readTree(f.controller.providerEventForRelay(raw))

            assertThat(VoiceTutorTranscriptMetadata.lessonRevision(stamped)).isEqualTo(5)
            assertThat(stamped.path("transcript").asText()).isEqualTo("지금 주제에서 이어서 이야기해 볼게요.")
            f.assertCallUnaffected()
        }

    @Test
    fun `MCP revision changes only the next response while old speech checkpoints and late ASR retain their starting epoch`(): Unit =
        Fixture().use { f ->
            val oldResponse = f.beginTutor(1)
            f.done(oldResponse, listOf("update-level"), spoken = true)
            f.start(2)
            f.complete("update-level", revision = 1)
            f.ackAll()

            val lateTeacher = f.tutorTranscript(oldResponse.id, "기존 난도의 질문을 마저 설명할게요.", forgedRevision = 91)
            assertThat(f.controller.observeProviderEvent(lateTeacher))
                .isEqualTo(VoiceTutorProviderRelayDisposition.FORWARD_ONLY)
            assertThat(f.revision(lateTeacher)).isZero()

            f.advance(Duration.ofSeconds(12))
            f.controller.fireContinuousSpeechDeadline()
            // ASR may arrive before its commit ACK. Binding still uses the
            // earlier speech start, never this later completion/current epoch.
            f.transcript("old-checkpoint", "그 질문에 대해 아직 설명하는 중이에요.", forgedRevision = 91)
            f.commit("old-checkpoint")
            // A completed provider response is not yet a successful spoken
            // boundary. Assessment must wait for the exact output stop and its
            // post-relay acknowledgement.
            assertThat(f.responses()).hasSize(2) // Opening and the old tutor response.

            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("old-tail")
            f.transcript("old-tail", "제 답변은 여기까지예요.")
            assertThat(f.responses()).hasSize(2)
            f.stopped(oldResponse.id)
            f.assess()
            assertThat(f.publish("old-checkpoint")).isZero()
            assertThat(f.publish("old-tail")).isZero()

            val next = f.created("new-question")
            assertThat(f.revision(f.tutorTranscript(next.id, "변경된 난도로 질문할게요."))).isEqualTo(1)
            f.done(next)
            f.stopped(next.id)
            f.start(3)
            f.advance(Duration.ofSeconds(1))
            f.stop(3)
            assertThat(f.accept("new-answer", "새 난도 질문에 대한 제 답변이에요.")).isEqualTo(1)
            f.assertCallUnaffected()
        }

    @Test
    fun `stale duplicate failed and out of order tool results cannot rewrite the lesson revision`(): Unit = Fixture(initialRevision = 4).use { f ->
        val old = f.beginTutor(1)
        f.done(old, listOf("successful", "failed", "older"))
        assertThat(f.controller.completeToolExecution("unknown-call", result(99))).isFalse()
        f.complete("successful", revision = 6)
        assertThat(f.controller.completeToolExecution("successful", result(99))).isFalse()
        f.complete("failed", revision = 99, failed = true)
        f.complete("older", revision = 5)
        f.ackAll()

        val next = f.created("after-tools")

        assertThat(f.revision(f.tutorTranscript(next.id, "새 질문입니다.", forgedRevision = 99))).isEqualTo(6)
        assertThat(f.revision(f.tutorTranscript(old.id, "이전 질문입니다."))).isEqualTo(4)
        f.assertCallUnaffected()
    }

    @Test
    fun `a delayed native tail combining rapid utterances retains the first epoch across an MCP change`(): Unit = Fixture().use { f ->
        val old = f.beginTutor(1)
        f.done(old, listOf("update"))
        f.start(2)
        f.advance(Duration.ofSeconds(12))
        f.controller.fireContinuousSpeechDeadline()
        assertThat(f.accept("checkpoint", "기존 질문에 관한 답변을 이어가는 중이에요.")).isZero()
        f.stop(2) // Deferred tail: checkpoint was just committed.
        f.complete("update", revision = 1)
        f.start(3)
        f.advance(Duration.ofMillis(100))
        f.stop(3) // Same native buffer, now with a newer speech-start epoch.
        f.advance(Duration.ofMillis(200))
        f.controller.flushDelayedStopCommit(2)

        assertThat(f.accept("merged-tail", "아까 답변을 마치고 다음 난도로 넘어갈게요.")).isZero()
        f.ackAll()
        val next = f.created("after-merged-tail")
        assertThat(f.revision(f.tutorTranscript(next.id, "다음 질문입니다."))).isEqualTo(1)
        f.assertCallUnaffected()
    }

    @Test
    fun `unknown response IDs stay dropped and missing revision evidence remains unassigned without closing a call`(): Unit =
        Fixture(initialRevision = 3, finishOpening = false).use { f ->
            val unknownTutor = f.tutorTranscript("unrecognized-response", "원본 발화", forgedRevision = 0)
            assertThat(f.controller.observeProviderEvent(unknownTutor)).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
            assertThat(f.revision(unknownTutor)).isEqualTo(-1)
            val unknownInput = f.inputTranscript("unrecognized-input", "원본 사용자 발화", forgedRevision = 0)
            assertThat(f.revision(unknownInput)).isEqualTo(-1)
            assertThat(mapper.readTree(f.controller.providerEventForRelay(unknownInput)).path("transcript").asText())
                .isEqualTo("원본 사용자 발화")
            f.assertCallUnaffected()
        }

    @Test
    fun `bounded response mapping eviction never assigns a late item to revision zero or the latest lesson`(): Unit = Fixture(initialRevision = 7).use { f ->
        repeat(70) { index ->
            val tutor = f.beginTutor(index.toLong() + 1)
            f.done(tutor)
            f.stopped(tutor.id)
        }
        val evicted = f.tutorTranscript("opening", "遅れて到着した元の発話", forgedRevision = 7)

        assertThat(f.revision(evicted)).isEqualTo(-1)
        assertThat(f.controller.observeProviderEvent(evicted)).isEqualTo(VoiceTutorProviderRelayDisposition.DROP)
        f.assertCallUnaffected()
    }

    @Test
    fun `legacy revision zero compatibility never accepts provider supplied metadata as authority`(): Unit {
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12), responseTimeout = Duration.ofSeconds(60),
        )
        try {
            val raw = mapper.writeValueAsString(mapOf(
                "type" to "conversation.item.input_audio_transcription.completed",
                "item_id" to "legacy-item", "transcript" to "기존 연결의 원문입니다.",
                VoiceTutorTranscriptMetadata.LESSON_REVISION to 99,
            ))
            assertThat(VoiceTutorTranscriptMetadata.lessonRevision(mapper.readTree(controller.providerEventForRelay(raw)))).isZero()
            assertThat(controller.acceptsInputEvents()).isTrue()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `late pre-question meaningful input cannot borrow a later noise start to satisfy spoken confirmation`(): Unit =
        Fixture(finishOpening = false).use { f ->
            f.start(1)
            f.advance(Duration.ofSeconds(1))
            f.stop(1)
            f.commit("before-question")
            f.transcript("before-question", "이전 질문에 대한 답변이에요.")
            f.done(Response("opening", f.responses().single()))
            f.stopped("opening")
            val questionBoundary = f.controller.mutationDialogueBoundary()
            assertThat(questionBoundary.latestAcceptedLearnerSpeechStartedOrder).isZero()

            f.start(2) // A later acoustic event is not yet accepted learner intent.
            f.assess()
            f.publish("before-question")
            val oldPublication = f.controller.mutationDialogueBoundary()
            assertThat(oldPublication.latestAcceptedLearnerSpeechStartedOrder).isPositive()
            assertThat(oldPublication.precedingTutorSpeechStoppedOrder).isZero()
            assertThat(oldPublication.precedingSpokenResponseGeneration).isZero()

            f.advance(Duration.ofSeconds(1))
            f.stop(2)
            f.commit("later-noise")
            f.transcript("later-noise", "음… 어…")
            f.assess(VoiceTutorInputDecision.NON_COMMUNICATIVE)
            f.deleted("later-noise")
            val afterNoise = f.controller.mutationDialogueBoundary()
            assertThat(afterNoise.latestAcceptedLearnerSpeechStartedOrder)
                .isEqualTo(oldPublication.latestAcceptedLearnerSpeechStartedOrder)
            assertThat(afterNoise.precedingTutorSpeechStoppedOrder).isZero()
            assertThat(afterNoise.precedingSpokenResponseGeneration).isZero()
            f.assertCallUnaffected()
        }

    @Test
    fun `accepted learner boundary advances only after exact meaningful publication succeeds`(): Unit = Fixture().use { f ->
        f.start(1)
        f.advance(Duration.ofSeconds(1))
        f.stop(1)
        f.commit("confirmation")
        f.transcript("confirmation", "방금 확인한 내용을 승인할게요.")
        f.assess()
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder).isZero()

        f.publish("confirmation")
        val published = f.controller.mutationDialogueBoundary()
        assertThat(published.precedingTutorSpeechStoppedOrder).isPositive()
        assertThat(published.precedingSpokenResponseGeneration).isEqualTo(1)
        assertThat(published.latestAcceptedLearnerSpeechStartedOrder).isGreaterThan(published.precedingTutorSpeechStoppedOrder)
        f.start(2)
        f.controller.confirmInputPublished("confirmation") // Duplicate ACK must not adopt this newer raw start.
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder)
            .isEqualTo(published.latestAcceptedLearnerSpeechStartedOrder)
        f.assertCallUnaffected()
    }

    @Test
    fun `a mixed tool responses late audio tail is not a later spoken confirmation question`(): Unit = Fixture().use { f ->
        val preview = f.beginTutor(1)
        f.done(preview, listOf("preview-delete"), spoken = true)
        val previewBoundary = f.controller.mutationDialogueBoundary()
        f.complete("preview-delete", revision = 0)
        f.ackAll()
        f.stopped("unrelated-response")
        assertThat(f.controller.mutationDialogueBoundary()).isEqualTo(previewBoundary)

        f.stopped(preview.id)
        val justOldTail = f.controller.mutationDialogueBoundary()
        // Until another meaningful learner item is persisted, the snapshot
        // still belongs to the learner turn that requested the preview.
        assertThat(justOldTail.precedingSpokenResponseGeneration).isLessThanOrEqualTo(previewBoundary.responseGeneration)
        assertThat(justOldTail.responseGeneration).isGreaterThan(previewBoundary.responseGeneration)

        val confirmation = f.created("spoken-confirmation")
        f.done(confirmation)
        f.stopped(confirmation.id)
        val actualQuestion = f.controller.mutationDialogueBoundary()
        assertThat(actualQuestion.precedingSpokenResponseGeneration).isEqualTo(previewBoundary.precedingSpokenResponseGeneration)
        f.stopped(confirmation.id)
        assertThat(f.controller.mutationDialogueBoundary()).isEqualTo(actualQuestion)
        f.start(2)
        f.advance(Duration.ofSeconds(1))
        f.stop(2)
        f.accept("after-question", "방금 말한 항목을 삭제해 주세요.")
        val accepted = f.controller.mutationDialogueBoundary()
        assertThat(accepted.precedingSpokenResponseGeneration).isGreaterThan(previewBoundary.responseGeneration)
        assertThat(accepted.precedingTutorSpeechStoppedOrder).isPositive()
        assertThat(accepted.latestAcceptedLearnerSpeechStartedOrder).isGreaterThan(accepted.precedingTutorSpeechStoppedOrder)

        // The confirming tool response may itself say "처리할게요". Its
        // newer output tail must not replace the question boundary that the
        // accepted learner confirmation actually followed.
        val confirmingTool = f.created("confirming-tool")
        f.done(confirmingTool, listOf("confirm-delete"), spoken = true)
        f.stopped(confirmingTool.id)
        assertThat(f.controller.mutationDialogueBoundary()).isEqualTo(accepted)
        f.complete("confirm-delete", revision = 0)
        assertThat(f.controller.mutationDialogueBoundary()).isEqualTo(accepted)
        f.assertCallUnaffected()
    }

    @Test
    fun `storage refusal clears prior mutation consent but consumes each handled input once and keeps the call usable`(): Unit = Fixture().use { f ->
        val earlier = f.beginTutor(1)
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder).isPositive()
        f.done(earlier)
        f.stopped(earlier.id)
        f.start(2)
        f.advance(Duration.ofSeconds(1))
        f.stop(2)
        f.commit("not-stored")
        f.transcript("not-stored", "이 입력은 저장 한도나 중복으로 추가되지 않았어요.")
        f.assess()
        f.publish("not-stored", persisted = false)
        assertThat(f.responses()).hasSize(3)
        val refused = f.controller.mutationDialogueBoundary()
        assertThat(refused.latestAcceptedLearnerSpeechStartedOrder).isZero()
        assertThat(refused.precedingTutorSpeechStoppedOrder).isZero()
        assertThat(refused.precedingSpokenResponseGeneration).isZero()

        val reply = f.created("reply-to-handled-input")
        f.done(reply)
        f.stopped(reply.id)
        // A replay or forged successful duplicate ACK cannot revive the
        // handled publication or cause the same question to be answered again.
        f.transcript("not-stored", "중복 전사")
        f.commit("not-stored")
        f.controller.confirmInputPublished("not-stored", persisted = true)
        assertThat(f.responses()).hasSize(3)
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder).isZero()

        f.start(3)
        f.advance(Duration.ofSeconds(1))
        f.stop(3)
        f.accept("new-stored", "새로 저장되는 별개의 의미 있는 응답입니다.")
        assertThat(f.responses()).hasSize(4)
        assertThat(f.controller.mutationDialogueBoundary().latestAcceptedLearnerSpeechStartedOrder).isPositive()
        f.assertCallUnaffected()
    }

    private data class Response(val id: String, val create: JsonNode)

    private class Fixture(initialRevision: Long = 0, finishOpening: Boolean = true) : AutoCloseable {
        private val now = AtomicLong()
        val controls = CopyOnWriteArrayList<JsonNode>()
        private val actions = CopyOnWriteArrayList<VoiceTutorInputTurnCoordinator.Action>()
        private val relayedPublications = mutableMapOf<String, JsonNode>()
        val questionAssessments = CopyOnWriteArrayList<VoiceTutorSpokenQuestionAssessmentAction>()
        val feedbackAssessments = CopyOnWriteArrayList<VoiceTutorSpokenFeedbackAssessmentAction>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        val controller = VoiceTutorDuplexTurnController(
            continuousSpeechLimit = Duration.ofSeconds(12), responseTimeout = Duration.ofSeconds(60),
            nanoTime = now::get, transport = VoiceTutorRealtimeTransport.WEBRTC_SIDEBAND,
            inputCoordinator = VoiceTutorInputTurnCoordinator(), toolsEnabled = true,
            initialLessonRevision = initialRevision,
        )
        private val subscriptions = listOf(
            controller.providerEvents().subscribe({ controls.add(mapper.readTree(it)) }, errors::add),
            controller.inputActions().subscribe(actions::add, errors::add),
            controller.spokenQuestionAssessmentActions().subscribe(questionAssessments::add, errors::add),
            controller.spokenFeedbackAssessmentActions().subscribe(feedbackAssessments::add, errors::add),
            controller.toolActions().subscribe({}, errors::add),
        )

        init {
            controller.startOpeningResponse()
            val opening = created("opening")
            if (finishOpening) {
                done(opening)
                stopped(opening.id)
            }
        }

        fun advance(duration: Duration) { now.addAndGet(duration.toNanos()) }
        fun start(sequence: Long) = speech(true, sequence)
        fun stop(sequence: Long) = speech(false, sequence)
        private fun speech(started: Boolean, sequence: Long) {
            controller.observeClientEvent(mapper.writeValueAsString(mapOf(
                "type" to if (started) VoiceTutorRealtimeContract.SPEECH_STARTED_EVENT else VoiceTutorRealtimeContract.SPEECH_STOPPED_EVENT,
                "sequence" to sequence,
            )))
        }
        fun beginTutor(sequence: Long): Response {
            start(sequence)
            advance(Duration.ofSeconds(1))
            stop(sequence)
            accept("learner-$sequence", "학습 난도와 질문에 대해 알려 주세요.")
            return created("tutor-$sequence")
        }
        fun finishOpeningWithQuestion(question: String) {
            val opening = Response("opening", responses().single())
            provider("output_audio_buffer.started", "response_id" to opening.id)
            controller.observeProviderEvent(tutorTranscript(opening.id, question))
            done(opening, spoken = true)
            stopped(opening.id)
        }
        fun created(id: String): Response = Response(id, responses().last()).also {
            emitResponse("response.created", it)
        }
        fun done(
            response: Response,
            calls: List<String> = emptyList(),
            spoken: Boolean = false,
            toolName: String = "update_study",
        ) = doneCalls(response, calls.map { it to toolName }, spoken)

        fun doneCalls(
            response: Response,
            calls: List<Pair<String, String>> = emptyList(),
            spoken: Boolean = false,
        ) {
            val output = buildList<Map<String, Any>> {
                if (spoken) add(mapOf(
                    "type" to "message", "role" to "assistant", "id" to "message-${response.id}",
                    "content" to listOf(mapOf("type" to "audio", "transcript" to "기존 질문을 이어서 설명할게요.")),
                ))
                calls.forEach { (id, name) -> add(mapOf(
                    "type" to "function_call", "status" to "completed", "call_id" to id,
                    "name" to name, "arguments" to "{}",
                )) }
            }
            if (spoken) provider("output_audio_buffer.started", "response_id" to response.id)
            emitResponse("response.done", response, output)
        }
        private fun emitResponse(type: String, response: Response, output: List<Map<String, Any>> = emptyList()) = provider(
            type, "response" to mapOf(
                "id" to response.id, "status" to if (type == "response.created") "in_progress" else "completed",
                "metadata" to mapOf(VoiceTutorRealtimeContract.RESPONSE_TOKEN_METADATA_KEY to response.create.path("event_id").asText()),
                "output" to output,
            ),
        )
        fun stopped(id: String) = provider("output_audio_buffer.stopped", "response_id" to id)
        fun stoppedWithBoundary(id: String): VoiceTutorProviderObservation =
            controller.observeProviderEventWithPostRelay(mapper.writeValueAsString(mapOf(
                "type" to "output_audio_buffer.stopped", "response_id" to id,
            )))
        fun commit(id: String) = provider("input_audio_buffer.committed", "item_id" to id)
        fun deleted(id: String) = provider("conversation.item.deleted", "item_id" to id)
        fun transcript(id: String, text: String, forgedRevision: Long = 99) {
            controller.observeProviderEvent(inputTranscript(id, text, forgedRevision))
        }
        fun assess(
            decision: VoiceTutorInputDecision = VoiceTutorInputDecision.MEANINGFUL,
            intent: VoiceTutorInputIntent = VoiceTutorInputIntent.NONE,
            currentTranscriptAnswersStudyQuestion: Boolean =
                intent == VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
        ) {
            val assessment = actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Assess>().last()
            controller.completeInputAssessment(assessment.token, Result.success(VoiceTutorInputAssessmentResult(
                assessment.utterances.map {
                    VoiceTutorInputItemAssessment(
                        it.itemId,
                        decision,
                        intent,
                        currentTranscriptAnswersStudyQuestion = currentTranscriptAnswersStudyQuestion,
                    )
                },
            )))
        }
        fun publication(id: String): VoiceTutorInputTurnCoordinator.Action.Publish =
            actions.filterIsInstance<VoiceTutorInputTurnCoordinator.Action.Publish>().last { it.itemId == id }

        fun publicationForRelay(id: String): JsonNode {
            return relayedPublications.getOrPut(id) {
                val action = publication(id)
                mapper.readTree(controller.providerEventForRelay(
                    action.rawEvent,
                    verifiedStudyAnswer =
                        !action.checkpoint && action.intent == VoiceTutorInputIntent.ANSWER_TO_STUDY_QUESTION,
                    verifiedLearnerQuestion =
                        !action.checkpoint && action.intent == VoiceTutorInputIntent.ASK_STUDY_QUESTION,
                    speechSequence = action.sequence,
                    checkpoint = action.checkpoint,
                    currentTranscriptAnswersStudyQuestion =
                        action.currentTranscriptAnswersStudyQuestion,
                ))
            }
        }
        fun publish(id: String, persisted: Boolean = true): Long {
            val publication = publication(id)
            assertThat(controller.canPublishInput(id)).isTrue()
            // Production stamps the exact action immediately before persistence.
            // Repeating the same server-owned projection is intentionally
            // idempotent and must not discard a pending multipart answer group.
            val revision = VoiceTutorTranscriptMetadata.lessonRevision(publicationForRelay(id))
            controller.confirmInputPublished(id, persisted = persisted)
            return revision
        }
        fun accept(id: String, text: String): Long {
            commit(id)
            transcript(id, text)
            assess()
            return publish(id)
        }
        fun complete(id: String, revision: Long, failed: Boolean = false) {
            assertThat(controller.beginToolExecution(id)).isTrue()
            assertThat(controller.completeToolExecution(id, result(revision, failed))).isTrue()
        }
        fun completeFocus(
            id: String,
            revision: Long = 1,
            studyId: Long = 202,
            topic: String = "Redis",
            difficulty: Int = 4,
        ) {
            val selection = VoiceTutorLessonFocusSelection(
                VoiceTutorLessonFocus(studyId, revision),
                VoiceTutorStudySnapshot(studyId, null, topic, difficulty, revision),
            )
            assertThat(controller.beginToolExecution(id)).isTrue()
            assertThat(controller.completeToolExecution(
                id,
                VoiceTutorMcpToolResult(
                    output = "{}",
                    isError = false,
                    lessonRevision = revision,
                    lessonFocus = selection,
                ),
            )).isTrue()
        }
        fun selectFocus(sequence: Long = 1): Response {
            val selecting = beginTutor(sequence)
            done(selecting, listOf("focus-$sequence"), toolName = "select_voice_study")
            completeFocus("focus-$sequence")
            ackAll()
            return created("study-question-$sequence")
        }
        fun speak(response: Response, text: String, contentIndex: Int = 0) {
            controller.observeProviderEvent(tutorTranscript(response.id, text, contentIndex = contentIndex))
            done(response, spoken = true)
            stopped(response.id)
        }
        fun completeQuestionAssessment(accepted: Boolean): VoiceTutorPostRelayBoundary {
            val action = questionAssessments.last()
            val boundary = requireNotNull(
                controller.completeSpokenQuestionAssessment(action.token, Result.success(accepted)),
            )
            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
            return boundary
        }
        fun completeFeedbackAssessment(accepted: Boolean): VoiceTutorPostRelayBoundary {
            val action = feedbackAssessments.last()
            val boundary = requireNotNull(
                controller.completeSpokenFeedbackAssessment(action.token, Result.success(accepted)),
            )
            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
            return boundary
        }
        fun failFeedbackAssessment(error: Exception): VoiceTutorPostRelayBoundary {
            val action = feedbackAssessments.last()
            val boundary = requireNotNull(
                controller.completeSpokenFeedbackAssessment(action.token, Result.failure(error)),
            )
            assertThat(controller.acknowledgePostRelayBoundary(boundary.token)).isTrue()
            return boundary
        }
        fun ackAll() {
            controls.filter { it.path("type").asText() == "conversation.item.create" }
                .forEach { provider("conversation.item.added", "item" to it.path("item")) }
        }
        fun responses() = controls.filter { it.path("type").asText() == "response.create" }
        fun inputTranscript(id: String, text: String, forgedRevision: Long) = mapper.writeValueAsString(mapOf(
            "type" to "conversation.item.input_audio_transcription.completed", "item_id" to id, "transcript" to text,
            VoiceTutorTranscriptMetadata.LESSON_REVISION to forgedRevision,
        ))
        fun tutorTranscript(
            id: String,
            text: String,
            forgedRevision: Long = 99,
            contentIndex: Int = 0,
        ) = mapper.writeValueAsString(mapOf(
            "type" to "response.output_audio_transcript.done", "response_id" to id, "item_id" to "message-$id",
            "content_index" to contentIndex, "transcript" to text,
            VoiceTutorTranscriptMetadata.LESSON_REVISION to forgedRevision,
        ))
        fun revision(raw: String) = VoiceTutorTranscriptMetadata.lessonRevision(mapper.readTree(controller.providerEventForRelay(raw)))
        private fun provider(type: String, vararg fields: Pair<String, Any>) {
            controller.observeProviderEvent(mapper.writeValueAsString(mapOf("type" to type, *fields)))
        }
        fun assertCallUnaffected() {
            assertThat(controller.acceptsInputEvents()).isTrue()
            assertThat(errors).isEmpty()
            assertThat(controls.map { it.path("type").asText() })
                .doesNotContain("response.cancel", "conversation.item.truncate", "input_audio_buffer.clear", "output_audio_buffer.clear")
        }
        override fun close() {
            controller.close()
            subscriptions.forEach { it.dispose() }
        }
    }

    private companion object {
        val mapper = JsonMapperProvider.mapper
        fun result(revision: Long, failed: Boolean = false) = VoiceTutorMcpToolResult(
            output = "{}", isError = failed, lessonRevision = revision,
        )
    }
}
