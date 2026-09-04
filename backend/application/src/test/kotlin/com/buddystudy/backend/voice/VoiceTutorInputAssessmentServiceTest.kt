package com.buddystudy.backend.voice

import com.buddystudy.backend.config.VoiceTutorInputAssessmentProperties
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentException
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentFailure
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentRequest
import com.buddystudy.backend.voice.application.model.VoiceTutorInputAssessmentResult
import com.buddystudy.backend.voice.application.model.VoiceTutorInputDecision
import com.buddystudy.backend.voice.application.model.VoiceTutorInputIntent
import com.buddystudy.backend.voice.application.model.VoiceTutorInputItemAssessment
import com.buddystudy.backend.voice.application.model.VoiceTutorInputUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorPersistedLearnerUtterance
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetCandidate
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyMutationProposal
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetOffer
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetSingleChildEdge
import com.buddystudy.backend.voice.application.model.VoiceTutorStudyTargetTraversal
import com.buddystudy.backend.voice.application.port.outbound.VoiceTutorInputAssessmentPort
import com.buddystudy.backend.voice.application.service.VoiceTutorInputAssessmentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.util.concurrent.atomic.AtomicInteger

class VoiceTutorInputAssessmentServiceTest {
    @Test
    fun `spoken mutation proposal accepts natural yes but invalid or checkpoint proposals never reach provider`() =
        runBlocking<Unit> {
            val proposal = VoiceTutorStudyMutationProposal(
                proposalId = "proposal-1", intent = VoiceTutorInputIntent.UPDATE_STUDY,
                targetStudyId = 84, targetTopic = "스프링", difficulty = 7,
                tutorAudioTranscript = "스프링을 레벨 7로 바꿀까요?",
            )
            val input = VoiceTutorInputUtterance("yes", "응", mutationProposal = proposal)
            val calls = AtomicInteger()
            val service = service { request ->
                calls.incrementAndGet()
                VoiceTutorInputAssessmentResult(listOf(VoiceTutorInputItemAssessment(
                    request.utterances.single().itemId, VoiceTutorInputDecision.MEANINGFUL,
                    intent = VoiceTutorInputIntent.CONFIRM_STUDY_MUTATION,
                    mutationProposalId = proposal.proposalId,
                )))
            }
            assertThat(service.assess(request().copy(utterances = listOf(input))).decisions.single().intent)
                .isEqualTo(VoiceTutorInputIntent.CONFIRM_STUDY_MUTATION)
            for (invalid in listOf(
                input.copy(checkpoint = true),
                input.copy(mutationProposal = proposal.copy(tutorAudioTranscript = "")),
                input.copy(mutationProposal = proposal.copy(difficulty = 11)),
            )) {
                val failure = runCatching { service.assess(request().copy(utterances = listOf(invalid))) }.exceptionOrNull()
                assertThat((failure as VoiceTutorInputAssessmentException).reason)
                    .isEqualTo(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
            }
            assertThat(calls.get()).isEqualTo(1)
        }

    @Test
    fun `short answers names numbers partial ideas and hesitation reach provider unchanged`() = runBlocking<Unit> {
        val originals = listOf("응", "아니", "김민수", "2", "주제 알려줘", "레디스는 데이터를", "  어, 준비됐어.  ", "음...")
            .mapIndexed { index, text -> VoiceTutorInputUtterance("item_$index", text) }
        var observed: VoiceTutorInputAssessmentRequest? = null
        val service = service { request ->
            observed = request
            result(request)
        }
        val request = request().copy(utterances = originals)

        val actual = service.assess(request)

        assertThat(observed).isEqualTo(request)
        assertThat(observed!!.utterances.map { it.transcript }).containsExactlyElementsOf(originals.map { it.transcript })
        assertThat(actual.decisions).hasSize(originals.size)
        // This verifies no local semantic heuristic; mocked labels are not a
        // claim of live GPT accuracy on the example utterances.
    }

    @Test
    fun `provider non communicative verdict is preserved without rewriting or filtering the batch`() = runBlocking<Unit> {
        val request = request().copy(utterances = listOf(
            VoiceTutorInputUtterance("item_noise", ""), VoiceTutorInputUtterance("item_answer", "네"),
        ))
        val expected = VoiceTutorInputAssessmentResult(listOf(
            VoiceTutorInputItemAssessment("item_noise", VoiceTutorInputDecision.NON_COMMUNICATIVE),
            VoiceTutorInputItemAssessment("item_answer", VoiceTutorInputDecision.MEANINGFUL),
        ))

        assertThat(service { expected }.assess(request)).isEqualTo(expected)
        assertThat(request.utterances[0].transcript).isEmpty()
        assertThat(request.utterances[1].transcript).isEqualTo("네")
    }

    @Test
    fun `semantic mutation remains meaningful configuration and cannot be forged into a study answer`() =
        runBlocking<Unit> {
            val request = request().copy(utterances = listOf(
                VoiceTutorInputUtterance(
                    "mutation-item",
                    "Spring 주제 이름을 Spring Boot로 바꾸고 레벨을 7로 수정해줘.",
                ),
            ))
            val mutation = VoiceTutorInputItemAssessment(
                itemId = "mutation-item",
                decision = VoiceTutorInputDecision.MEANINGFUL,
                intent = VoiceTutorInputIntent.NONE,
                currentTranscriptAnswersStudyQuestion = false,
            )

            val accepted = service { VoiceTutorInputAssessmentResult(listOf(mutation)) }.assess(request)

            assertThat(accepted.decisions).containsExactly(mutation)

            val forged = runCatching {
                service {
                    VoiceTutorInputAssessmentResult(listOf(
                        mutation.copy(currentTranscriptAnswersStudyQuestion = true),
                    ))
                }.assess(request)
            }.exceptionOrNull()
            assertThat(forged).isInstanceOf(VoiceTutorInputAssessmentException::class.java)
            assertThat((forged as VoiceTutorInputAssessmentException).reason)
                .isEqualTo(VoiceTutorInputAssessmentFailure.INVALID_RESULT)
        }

    @Test
    fun `reordered complete provider decisions correlate to the original item order`() = runBlocking<Unit> {
        val request = request().copy(utterances = listOf(
            VoiceTutorInputUtterance("first", "네"), VoiceTutorInputUtterance("second", "계속해 주세요"),
        ))

        val actual = service { result(it).copy(decisions = result(it).decisions.reversed()) }.assess(request)

        assertThat(actual.decisions.map { it.itemId }).containsExactly("first", "second")
    }

    @Test
    fun `missing unknown duplicated or extra decisions are failures not implicit filler`() = runBlocking<Unit> {
        val request = request().copy(utterances = listOf(
            VoiceTutorInputUtterance("first", "네"), VoiceTutorInputUtterance("second", "계속해 주세요"),
        ))
        for (ids in listOf(emptyList(), listOf("first"), listOf("first", "foreign"),
                           listOf("first", "first"), listOf("first", "second", "extra"))) {
            val service = service {
                VoiceTutorInputAssessmentResult(ids.map {
                    VoiceTutorInputItemAssessment(it, VoiceTutorInputDecision.NON_COMMUNICATIVE)
                })
            }
            val error = runCatching { service.assess(request) }.exceptionOrNull()
            assertThat((error as VoiceTutorInputAssessmentException).reason)
                .describedAs("IDs: %s", ids).isEqualTo(VoiceTutorInputAssessmentFailure.INVALID_RESULT)
        }
    }

    @Test
    fun `invalid or over budget input never invokes the provider`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val service = service { calls.incrementAndGet(); result(it) }
        val base = request()
        val cases = listOf(
            base.copy(userId = 0), base.copy(language = ""), base.copy(language = "a".repeat(36)),
            base.copy(teacherContext = "a".repeat(4_001)), base.copy(utterances = emptyList()),
            base.copy(utterances = List(9) { VoiceTutorInputUtterance("item_$it", "네") }),
            base.copy(utterances = List(5) { VoiceTutorInputUtterance("item_$it", "a".repeat(4_000)) }),
            base.copy(utterances = listOf(VoiceTutorInputUtterance("item", "a".repeat(4_001)))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance("", "네"))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance("a".repeat(257), "네"))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance("item\n1", "네"))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance(
                "item", "네", checkpoint = true, sameSpeechContext = "이전 답변\n네",
            ))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance(
                "item", "네", sameSpeechContext = "이전 답변\n아니",
            ))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance(
                "item", "네", sameSpeechContext = "가".repeat(4_001) + "네",
            ))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance(
                "item", "네", priorPersistedLearnerUtterances = listOf(
                    VoiceTutorPersistedLearnerUtterance("item", "스프링"),
                ),
            ))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance(
                "item", "네", priorPersistedLearnerUtterances = List(4) {
                    VoiceTutorPersistedLearnerUtterance("prior-$it", "스프링")
                },
            ))),
            base.copy(utterances = listOf(VoiceTutorInputUtterance(
                "item", "네", priorPersistedLearnerUtterances = listOf(
                    VoiceTutorPersistedLearnerUtterance("prior", "스프링"),
                    VoiceTutorPersistedLearnerUtterance("prior", "레벨 세븐"),
                ),
            ))),
            base.copy(utterances = listOf(base.utterances[0], base.utterances[0])),
        )
        for (candidate in cases) {
            val error = runCatching { service.assess(candidate) }.exceptionOrNull()
            assertThat((error as VoiceTutorInputAssessmentException).reason)
                .isEqualTo(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
        }
        assertThat(calls).hasValue(0)
    }

    @Test
    fun `invalid or unbounded server target offers never reach the provider`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val service = service { calls.incrementAndGet(); result(it) }
        val valid = VoiceTutorStudyTargetOffer(
            offerId = 1,
            lessonRevision = 0,
            tutorResponseGeneration = 1,
            tutorSpeechStoppedOrder = 1,
            currentFocusStudyId = null,
            candidates = listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis")),
            tutorAudioTranscript = "Redis 주제로 이야기해 볼까요?",
            candidateTraversals = mapOf(101L to VoiceTutorStudyTargetTraversal()),
        )
        val invalidOffers = listOf(
            valid.copy(offerId = 0),
            valid.copy(lessonRevision = -1),
            valid.copy(tutorResponseGeneration = 0),
            valid.copy(tutorSpeechStoppedOrder = 0),
            valid.copy(currentFocusStudyId = 0),
            valid.copy(tutorAudioTranscript = " "),
            valid.copy(tutorAudioTranscript = "x".repeat(4_001)),
            valid.copy(candidates = emptyList()),
            valid.copy(candidates = List(17) { index ->
                VoiceTutorStudyTargetCandidate((index + 1).toLong(), null, "Topic $index")
            }),
            valid.copy(candidates = listOf(
                VoiceTutorStudyTargetCandidate(101, null, "Redis"),
                VoiceTutorStudyTargetCandidate(101, null, "Duplicate"),
            )),
            valid.copy(candidates = listOf(VoiceTutorStudyTargetCandidate(0, null, "Redis"))),
            valid.copy(candidates = listOf(VoiceTutorStudyTargetCandidate(101, 0, "Redis"))),
            valid.copy(candidates = listOf(VoiceTutorStudyTargetCandidate(101, null, " "))),
            valid.copy(candidates = listOf(VoiceTutorStudyTargetCandidate(101, null, "x".repeat(256)))),
            valid.copy(candidates = listOf(VoiceTutorStudyTargetCandidate(101, null, "Redis", difficulty = 11))),
            valid.copy(candidateTraversals = emptyMap()),
            valid.copy(candidateTraversals = mapOf(
                202L to VoiceTutorStudyTargetTraversal(),
            )),
            valid.copy(candidateTraversals = mapOf(
                101L to VoiceTutorStudyTargetTraversal(
                    singleChildEdges = listOf(
                        VoiceTutorStudyTargetSingleChildEdge(1, 2),
                        VoiceTutorStudyTargetSingleChildEdge(3, 101),
                    ),
                    terminalLeafStudyId = 101,
                ),
            )),
            valid.copy(candidateTraversals = mapOf(
                101L to VoiceTutorStudyTargetTraversal(terminalLeafStudyId = 202),
            )),
            valid.copy(candidateTraversals = mapOf(
                101L to VoiceTutorStudyTargetTraversal(
                    singleChildEdges = listOf(VoiceTutorStudyTargetSingleChildEdge(1, 101)),
                    terminalLeafStudyId = 101,
                ),
            )),
            valid.copy(
                candidates = listOf(VoiceTutorStudyTargetCandidate(101, 101, "Redis")),
                candidateTraversals = mapOf(101L to VoiceTutorStudyTargetTraversal()),
            ),
        )

        for (offer in invalidOffers) {
            val candidate = request().copy(utterances = listOf(
                VoiceTutorInputUtterance("item", "응", targetOffer = offer),
            ))
            val error = runCatching { service.assess(candidate) }.exceptionOrNull()
            assertThat((error as VoiceTutorInputAssessmentException).reason)
                .isEqualTo(VoiceTutorInputAssessmentFailure.INVALID_INPUT)
        }
        assertThat(calls).hasValue(0)
    }

    @Test
    fun `a target candidate must also be semantically attested as spoken in the final tutor audio`() = runBlocking<Unit> {
        val offer = VoiceTutorStudyTargetOffer(
            offerId = 1,
            lessonRevision = 0,
            tutorResponseGeneration = 1,
            tutorSpeechStoppedOrder = 1,
            currentFocusStudyId = null,
            candidates = listOf(
                VoiceTutorStudyTargetCandidate(101, null, "Redis"),
                VoiceTutorStudyTargetCandidate(202, null, "PostgreSQL"),
            ),
            tutorAudioTranscript = "Redis 주제로 이야기해 볼까요?",
            candidateTraversals = mapOf(
                101L to VoiceTutorStudyTargetTraversal(),
                202L to VoiceTutorStudyTargetTraversal(),
            ),
        )
        val request = request().copy(utterances = listOf(
            VoiceTutorInputUtterance("item", "PostgreSQL로 할게", targetOffer = offer),
        ))
        val invalid = listOf(
            VoiceTutorInputItemAssessment(
                "item", VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
                targetStudyId = 202, spokenCandidateStudyIds = emptyList(),
            ),
            VoiceTutorInputItemAssessment(
                "item", VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
                targetStudyId = 202, spokenCandidateStudyIds = listOf(101),
            ),
            VoiceTutorInputItemAssessment(
                "item", VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
                targetStudyId = 202, spokenCandidateStudyIds = listOf(999),
            ),
            VoiceTutorInputItemAssessment(
                "item", VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.NONE,
                spokenCandidateStudyIds = listOf(101),
            ),
        )

        for (assessment in invalid) {
            val error = runCatching {
                service { VoiceTutorInputAssessmentResult(listOf(assessment)) }.assess(request)
            }.exceptionOrNull()
            assertThat((error as VoiceTutorInputAssessmentException).reason)
                .describedAs("assessment: %s", assessment)
                .isEqualTo(VoiceTutorInputAssessmentFailure.INVALID_RESULT)
        }

        val valid = VoiceTutorInputItemAssessment(
            "item", VoiceTutorInputDecision.MEANINGFUL, VoiceTutorInputIntent.SELECT_SAVED_TOPIC,
            targetStudyId = 101, spokenCandidateStudyIds = listOf(101),
        )
        assertThat(service { VoiceTutorInputAssessmentResult(listOf(valid)) }.assess(request).decisions.single())
            .isEqualTo(valid)
    }

    @Test
    fun `a bounded admission wait preserves a fifth users meaningful turn during a short burst`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = service {
            if (calls.incrementAndGet() == 4) entered.complete(Unit)
            release.await()
            result(it)
        }
        val requests = (1L..4L).map { user -> async { service.assess(request().copy(userId = user)) } }
        entered.await()

        val fifth = async(start = CoroutineStart.UNDISPATCHED) {
            service.assess(request().copy(userId = 5))
        }
        assertThat(calls).hasValue(4)

        release.complete(Unit)
        requests.forEach { assertThat(it.await().decisions).hasSize(1) }
        assertThat(fifth.await().decisions).hasSize(1)
        assertThat(calls).hasValue(5)
    }

    @Test
    fun `assessment admission queue is size bounded and times out as busy`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = VoiceTutorInputAssessmentService(
            VoiceTutorInputAssessmentPort {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                result(it)
            },
            VoiceTutorInputAssessmentProperties(
                maxConcurrentAssessments = 1,
                admissionTimeoutMilliseconds = 25,
                maxQueuedAssessments = 1,
            ),
        )
        val active = async { service.assess(request().copy(userId = 1)) }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { service.assess(request().copy(userId = 2)) }.exceptionOrNull()
        }

        val overflow = runCatching { service.assess(request().copy(userId = 3)) }.exceptionOrNull()
        assertThat((overflow as VoiceTutorInputAssessmentException).reason)
            .isEqualTo(VoiceTutorInputAssessmentFailure.BUSY)
        assertThat(calls).hasValue(1)
        val timedOut = queued.await()
        assertThat((timedOut as VoiceTutorInputAssessmentException).reason)
            .isEqualTo(VoiceTutorInputAssessmentFailure.BUSY)

        release.complete(Unit)
        assertThat(active.await().decisions).hasSize(1)
    }

    @Test
    fun `own total timeout cancels provider releases permit and is not non communicative`() = runBlocking<Unit> {
        var stalled = true
        var canceled = false
        val service = VoiceTutorInputAssessmentService(
            VoiceTutorInputAssessmentPort {
                if (stalled) {
                    try { awaitCancellation() } finally { canceled = true }
                }
                result(it)
            },
            VoiceTutorInputAssessmentProperties(timeoutMilliseconds = 25, maxConcurrentAssessments = 1),
        )

        val error = runCatching { service.assess(request()) }.exceptionOrNull()

        assertThat((error as VoiceTutorInputAssessmentException).reason).isEqualTo(VoiceTutorInputAssessmentFailure.TIMEOUT)
        assertThat(canceled).isTrue()
        stalled = false
        assertThat(service.assess(request()).decisions.single().decision).isEqualTo(VoiceTutorInputDecision.MEANINGFUL)
    }

    @Test
    fun `caller cancellation remains cancellation and releases admission`() = runBlocking<Unit> {
        var stalled = true
        var canceled = false
        val service = VoiceTutorInputAssessmentService(
            VoiceTutorInputAssessmentPort {
                if (stalled) try { awaitCancellation() } finally { canceled = true }
                result(it)
            },
            VoiceTutorInputAssessmentProperties(maxConcurrentAssessments = 1),
        )
        val pending = async(start = CoroutineStart.UNDISPATCHED) { service.assess(request()) }

        pending.cancelAndJoin()

        assertThat(pending.isCancelled).isTrue()
        assertThat(canceled).isTrue()
        stalled = false
        assertThat(service.assess(request()).decisions).hasSize(1)
    }

    @Test
    fun `queued caller cancellation does not consume a permit or queue slot`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val service = VoiceTutorInputAssessmentService(
            VoiceTutorInputAssessmentPort {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                result(it)
            },
            VoiceTutorInputAssessmentProperties(
                maxConcurrentAssessments = 1,
                admissionTimeoutMilliseconds = 1_000,
                maxQueuedAssessments = 1,
            ),
        )
        val active = async { service.assess(request().copy(userId = 1)) }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            service.assess(request().copy(userId = 2))
        }

        queued.cancelAndJoin()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            service.assess(request().copy(userId = 3))
        }
        release.complete(Unit)

        assertThat(active.await().decisions).hasSize(1)
        assertThat(replacement.await().decisions).hasSize(1)
        assertThat(calls).hasValue(2)
    }

    @Test
    fun `upstream failures preserve fixed reason or redact cause without retry`() = runBlocking<Unit> {
        val secret = "private-utterance-and-provider-key"
        for (reason in listOf(VoiceTutorInputAssessmentFailure.REFUSED, VoiceTutorInputAssessmentFailure.INVALID_RESULT)) {
            val error = runCatching {
                service { throw VoiceTutorInputAssessmentException(reason) }.assess(request())
            }.exceptionOrNull()
            assertThat((error as VoiceTutorInputAssessmentException).reason).isEqualTo(reason)
        }
        val calls = AtomicInteger()
        val error = runCatching {
            service { calls.incrementAndGet(); throw IllegalStateException(secret) }.assess(request())
        }.exceptionOrNull()
        assertThat((error as VoiceTutorInputAssessmentException).reason).isEqualTo(VoiceTutorInputAssessmentFailure.UNAVAILABLE)
        assertThat(error.cause).isNull()
        assertThat(error.toString()).doesNotContain(secret)
        assertThat(calls).hasValue(1)

        val cancellation = CancellationException(secret)
        assertThat(runCatching { service { throw cancellation }.assess(request()) }.exceptionOrNull())
            // Coroutine stack-trace recovery can copy CancellationException;
            // cancellation semantics, not object identity, are the contract.
            .isInstanceOf(CancellationException::class.java)
            .hasMessage(secret)
    }

    @Test
    fun `request snapshot and diagnostics do not expose or rewrite private content`() = runBlocking<Unit> {
        val source = mutableListOf(VoiceTutorInputUtterance("private-item", "private-original-text"))
        val request = request().copy(teacherContext = "private-teacher-context", utterances = source)
        val entered = CompletableDeferred<VoiceTutorInputAssessmentRequest>()
        val release = CompletableDeferred<Unit>()
        val service = service {
            entered.complete(it)
            release.await()
            result(it)
        }
        val pending = async { service.assess(request) }
        val snapshot = entered.await()
        source.clear()
        source.add(VoiceTutorInputUtterance("replacement", "new-text"))
        release.complete(Unit)

        assertThat(snapshot.utterances.single().transcript).isEqualTo("private-original-text")
        val assessment = pending.await()
        assertThat(assessment.decisions.single().itemId).isEqualTo("private-item")
        for (value in listOf(snapshot.toString(), snapshot.utterances.toString(), assessment.toString())) {
            assertThat(value).doesNotContain("private-original-text", "private-teacher-context", "private-item")
        }
    }

    @Test
    fun `independent configuration binds without a new model or key property`() {
        val bound = Binder(MapConfigurationPropertySource(mapOf(
            "buddystudy.voice-tutor.input-assessment.timeout-milliseconds" to "1200",
            "buddystudy.voice-tutor.input-assessment.max-concurrent-assessments" to "2",
            "buddystudy.voice-tutor.input-assessment.admission-timeout-milliseconds" to "900",
            "buddystudy.voice-tutor.input-assessment.max-queued-assessments" to "7",
        ))).bind("buddystudy.voice-tutor.input-assessment", Bindable.of(VoiceTutorInputAssessmentProperties::class.java)).get()

        assertThat(bound.timeoutMilliseconds).isEqualTo(1_200)
        assertThat(bound.maxConcurrentAssessments).isEqualTo(2)
        assertThat(bound.admissionTimeoutMilliseconds).isEqualTo(900)
        assertThat(bound.maxQueuedAssessments).isEqualTo(7)
        assertThat(bound.maxUtterances).isEqualTo(8)
        assertThat(bound.maxTranscriptCharacters).isEqualTo(4_000)
    }

    private fun request() = VoiceTutorInputAssessmentRequest(
        userId = 7, language = "ko", teacherContext = "학습을 시작할 준비가 됐나요?",
        utterances = listOf(VoiceTutorInputUtterance("item_1", "응")),
    )

    private fun result(request: VoiceTutorInputAssessmentRequest) = VoiceTutorInputAssessmentResult(
        request.utterances.map { VoiceTutorInputItemAssessment(it.itemId, VoiceTutorInputDecision.MEANINGFUL) },
    )

    private fun service(block: suspend (VoiceTutorInputAssessmentRequest) -> VoiceTutorInputAssessmentResult) =
        VoiceTutorInputAssessmentService(VoiceTutorInputAssessmentPort { block(it) }, VoiceTutorInputAssessmentProperties())
}
