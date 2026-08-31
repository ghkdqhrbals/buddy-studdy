package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.port.inbound.VoiceTutorInputAssessmentUseCase
import com.buddystudy.backend.voice.application.service.VoiceTutorInputAssessmentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.TimeUnit

/**
 * Explicitly paid, opt-in evaluation of the production use case and HTTP adapter.
 * No user records, app session, database, microphone, audio, mocks or fallback model.
 * These labels and synthetic examples must stay frozen before observing model output.
 */
@EnabledIfEnvironmentVariable(named = "BUDDYSTUDY_LIVE_INPUT_ASSESSMENT", matches = "1")
class VoiceTutorInputAssessmentLiveTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `production GPT assesses a frozen bounded Korean held out set`() = runBlocking<Unit> {
        withTimeout(30_000) {
            // Read the one explicitly supplied credential only after the opt-in
            // condition. Missing configuration is a failure, not a skipped/pass run.
            val key = System.getenv("OPENAI_API_KEY_USER")?.takeIf { it.isNotBlank() }
                ?: throw AssertionError("Opt-in live assessment requires OPENAI_API_KEY_USER.")
            val properties = BuddyStudyProperties().apply { openai.userContentApiKey = key }
            val limits = VoiceTutorInputAssessmentProperties()
            check(properties.voiceTutor.summaryModel == "gpt-5.4") {
                "This frozen evaluation requires the existing gpt-5.4 summary model."
            }
            check(limits.timeoutMilliseconds == 5_000L && limits.maxConcurrentAssessments == 4) {
                "This evaluation requires unchanged production assessment deadline and admission defaults."
            }
            val useCase: VoiceTutorInputAssessmentUseCase = VoiceTutorInputAssessmentService(
                OpenAIVoiceTutorInputAssessmentAdapter(UserContentOpenAIKeyProvider(properties), properties),
                limits,
            )
            val batches = heldOutBatches()
            check(batches.size == 4 && batches.all { it.cases.size == 6 }) {
                "The live evaluation is bounded to four requests and 24 synthetic items."
            }

            val testStarted = System.nanoTime()
            val mismatchedIds = ArrayList<String>()
            var requests = 0
            for (batch in batches) {
                check(requests < 4) { "Live request bound exceeded." }
                requests += 1
                val requestStarted = System.nanoTime()
                val result = try {
                    // Sequential calls only: no retry, parallel second attempt,
                    // alternate prompt, lower-level port bypass, or model search.
                    useCase.assess(VoiceTutorInputAssessmentRequest(
                        userId = SYNTHETIC_USER_ID,
                        language = "ko",
                        teacherContext = batch.teacherContext,
                        utterances = batch.cases.map { VoiceTutorInputUtterance(it.id, it.transcript) },
                    ))
                } catch (error: CancellationException) {
                    println("voice_input_assessment stage=request request_id=${batch.id} " +
                        "elapsed_ms=${elapsedMilliseconds(requestStarted)} status=CANCELLED")
                    throw error
                } catch (error: VoiceTutorInputAssessmentException) {
                    println("voice_input_assessment stage=request request_id=${batch.id} " +
                        "elapsed_ms=${elapsedMilliseconds(requestStarted)} status=FAILED reason=${error.reason.name}")
                    // Deliberately no original exception/cause or HTTP payload in
                    // the JUnit failure. An API/schema/refusal error ends the run now.
                    throw AssertionError("Live assessment request ${batch.id} failed (${error.reason.name}).")
                } catch (_: Exception) {
                    println("voice_input_assessment stage=request request_id=${batch.id} " +
                        "elapsed_ms=${elapsedMilliseconds(requestStarted)} status=FAILED reason=UNAVAILABLE")
                    throw AssertionError("Live assessment request ${batch.id} failed (UNAVAILABLE).")
                }
                val elapsed = elapsedMilliseconds(requestStarted)
                println("voice_input_assessment stage=request request_id=${batch.id} " +
                    "elapsed_ms=$elapsed status=COMPLETED")
                // The production service has already rejected omissions,
                // duplicate/foreign IDs and reordered the exact matching result.
                for ((index, example) in batch.cases.withIndex()) {
                    val decision = result.decisions[index].decision
                    val matches = decision == example.expected
                    if (!matches) mismatchedIds += example.id
                    println("voice_input_assessment stage=item request_id=${batch.id} case_id=${example.id} " +
                        "decision=${decision.name} match=$matches elapsed_ms=$elapsed")
                }
            }
            println("voice_input_assessment stage=summary requests=$requests total_items=24 " +
                "matched_items=${24 - mismatchedIds.size} elapsed_ms=${elapsedMilliseconds(testStarted)}")
            if (mismatchedIds.isNotEmpty()) {
                throw AssertionError("Live assessment mismatched ${mismatchedIds.size}/24 fixed cases: " +
                    mismatchedIds.joinToString(","))
            }
        }
    }

    private fun heldOutBatches(): List<SyntheticBatch> = listOf(
        SyntheticBatch(
            id = "readiness",
            teacherContext = "오늘은 운영체제를 함께 공부할 거예요. 시작할 준비가 됐나요?",
            cases = listOf(
                meaningful("readiness_01", "응"),
                meaningful("readiness_02", "아니"),
                meaningful("readiness_03", "준비됐지"),
                meaningful("readiness_04", "어, 준비됐어."),
                nonCommunicative("readiness_05", "hmm..."),
                nonCommunicative("readiness_06", "uh..."),
            ),
        ),
        SyntheticBatch(
            id = "questions",
            teacherContext = "네트워크에서 궁금한 점이나 다음에 배우고 싶은 주제를 말해주세요.",
            cases = listOf(
                meaningful("questions_01", "주제 알려줘"),
                meaningful("questions_02", "왜 TCP는 연결을 먼저 맺어?"),
                meaningful("questions_03", "음, HTTP부터 설명해 줘."),
                nonCommunicative("questions_04", "음…"),
                nonCommunicative("questions_05", "어…"),
                nonCommunicative("questions_06", "[배경 소음]"),
            ),
        ),
        SyntheticBatch(
            id = "short_content",
            teacherContext = "프로세스 개수를 숫자로 답해 주세요. 함께 공부할 사람의 이름이나 떠오르는 생각도 말해도 좋아요.",
            cases = listOf(
                meaningful("short_content_01", "2"),
                meaningful("short_content_02", "김민수"),
                meaningful("short_content_03", "레디스는 데이터를"),
                meaningful("short_content_04", "음, 아직 잘 모르겠어."),
                nonCommunicative("short_content_05", "[기침]"),
                nonCommunicative("short_content_06", "[잡음]"),
            ),
        ),
        SyntheticBatch(
            id = "contextual_content",
            teacherContext = "설명에서 모르는 표현을 질문하거나, 다시 설명할 부분과 다음 진행 방향을 말해주세요.",
            cases = listOf(
                meaningful("contextual_content_01", "'음'이라는 단어는 무슨 뜻이야?"),
                meaningful("contextual_content_02", "아니, 다시 설명해줘."),
                meaningful("contextual_content_03", "어, 질문 하나 있어."),
                meaningful("contextual_content_04", "음... 그러니까 원자성이 보장되는 거네?"),
                nonCommunicative("contextual_content_05", "[마이크 바람 소리]"),
                nonCommunicative("contextual_content_06", "hmm"),
            ),
        ),
    )

    private fun meaningful(id: String, text: String) = SyntheticCase(id, text, VoiceTutorInputDecision.MEANINGFUL)

    private fun nonCommunicative(id: String, text: String) =
        SyntheticCase(id, text, VoiceTutorInputDecision.NON_COMMUNICATIVE)

    private fun elapsedMilliseconds(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private data class SyntheticBatch(val id: String, val teacherContext: String, val cases: List<SyntheticCase>) {
        override fun toString(): String = "SyntheticBatch(id=$id, caseCount=${cases.size})"
    }

    private data class SyntheticCase(val id: String, val transcript: String, val expected: VoiceTutorInputDecision) {
        override fun toString(): String = "SyntheticCase(id=$id, expected=$expected)"
    }

    private companion object {
        // No real account is looked up or charged: the use case has no app
        // quota/session/database dependency, only this synthetic safety identity.
        const val SYNTHETIC_USER_ID = 9_000_000_000_001L
    }
}
