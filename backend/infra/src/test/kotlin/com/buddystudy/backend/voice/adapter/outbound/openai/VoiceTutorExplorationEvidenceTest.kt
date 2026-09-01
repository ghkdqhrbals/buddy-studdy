package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorResultStatus
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorSessionStatus
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceTutorExplorationEvidenceTest {
    private val now = Instant.parse("2026-08-31T10:00:00Z")
    private val snapshot = VoiceTutorStudySnapshot(43, 42, "Redis eviction", 7)

    @Test
    fun `a graded answer and learner-led deeper question retain distinct roles and saved child level`() {
        val deeper = exchange().copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION,
            questionTurnId = 4, answerTurnIds = listOf(5), feedbackTurnIds = emptyList(),
            question = "LRU와 LFU는 어떻게 달라요?", answer = "최근 사용 시점과 사용 빈도의 차이입니다.",
            score = null, strengths = emptyList(), improvements = emptyList(),
        )
        val result = verify(exploration().copy(exchanges = listOf(exchange(), deeper)))

        assertThat(result.single().studyId).isEqualTo(43)
        assertThat(result.single().difficulty).isEqualTo(7)
        assertThat(result.single().depthSummary).contains("LRU", "LFU")
        assertThat(result.single().exchanges.map { it.kind }).containsExactly(VoiceTutorExchangeKind.TUTOR_QUESTION, VoiceTutorExchangeKind.LEARNER_QUESTION)
        assertThat(result.single().exchanges.first().score).isEqualTo(85)
        assertThat(result.single().exchanges.last().score).isNull()
    }

    @Test
    fun `brief meaningful learner answer remains an answer without a length threshold`() {
        val briefTurns = turns().map { if (it.id == 2L) it.copy(transcript = "응") else it }
        val answer = verify(exploration().copy(exchanges = listOf(exchange().copy(answer = "응"))), briefTurns).single().exchanges.single()

        assertThat(answer.answer).isEqualTo("응")
        assertThat(answer.answerTurnIds).containsExactly(2L)
    }

    @Test
    fun `translated or paraphrased feedback need not be a literal transcript quote`() {
        val result = verify(exploration().copy(exchanges = listOf(exchange().copy(
            question = "Explain the role of eviction.", answer = "Remove keys when memory is exhausted.",
            strengths = listOf("Understood the memory-pressure motivation."),
            improvements = listOf("Name the concrete replacement-policy differences."),
        )))).single().exchanges.single()

        assertThat(result.score).isEqualTo(85)
        assertThat(result.strengths.single()).startsWith("Understood")
    }

    @Test
    fun `missing role-mismatched foreign or out-of-order evidence drops only that exchange`() {
        val invalid = listOf(
            exchange().copy(questionTurnId = 999),
            exchange().copy(questionTurnId = 2),
            exchange().copy(answerTurnIds = listOf(3)),
            exchange().copy(answerTurnIds = listOf(2, 2)),
            exchange().copy(feedbackTurnIds = listOf(2)),
            exchange().copy(feedbackTurnIds = listOf(1)),
            exchange().copy(answerTurnIds = listOf(4, 2)),
        )
        for (bad in invalid) {
            assertThat(verify(exploration().copy(exchanges = listOf(bad, exchange()))).single().exchanges).containsExactly(exchange())
        }
        assertThat(verify(exploration(), turns().map { if (it.id == 2L) it.copy(sessionId = "another-session") else it })).isEmpty()
    }

    @Test
    fun `feedback ids must link to the exact answer and cannot cite setup next question or another revision`() {
        val invalidFeedbackRows = listOf(
            turn(3, VoiceTutorTranscriptRole.TUTOR, "85점입니다."),
            turn(3, VoiceTutorTranscriptRole.TUTOR, "85점입니다.").copy(studyAnswerTurnId = 999),
            turn(3, VoiceTutorTranscriptRole.TUTOR, "다음 질문입니다.")
                .copy(studyAnswerTurnId = 2, isStudyQuestion = true),
            turn(3, VoiceTutorTranscriptRole.TUTOR, "레벨을 바꿀까요?"),
            turn(3, VoiceTutorTranscriptRole.TUTOR, "85점입니다.")
                .copy(studyAnswerTurnId = 2, lessonRevision = 2),
        )
        invalidFeedbackRows.forEach { feedback ->
            val transcript = turns().map { if (it.id == 3L) feedback else it }
            assertThat(verify(exploration(), transcript)).isEmpty()
        }
    }

    @Test
    fun `an intervening tutor invalidates an old question link but a user checkpoint does not`() {
        val userCheckpoint = turn(6, VoiceTutorTranscriptRole.USER, "음, 생각 중").copy(sequenceNumber = 2)
        val answerAfterCheckpoint = turns().map { turn ->
            when (turn.id) {
                1L -> turn.copy(sequenceNumber = 1)
                2L -> turn.copy(sequenceNumber = 3)
                3L -> turn.copy(sequenceNumber = 4)
                4L -> turn.copy(sequenceNumber = 5)
                else -> turn.copy(sequenceNumber = 6)
            }
        } + userCheckpoint
        assertThat(verify(exploration(), answerAfterCheckpoint)).hasSize(1)

        val tutorSetup = userCheckpoint.copy(
            id = 7, providerItemId = "item-7", role = VoiceTutorTranscriptRole.TUTOR,
            transcript = "학습을 시작할까요?",
        )
        assertThat(verify(exploration(), answerAfterCheckpoint.filterNot { it.id == 6L } + tutorSetup)).isEmpty()
    }

    @Test
    fun `duplicate source IDs and duplicated generated questions cannot multiply assessments`() {
        assertThat(verify(exploration(), turns() + turns()[1])).isEmpty()
        assertThat(verify(exploration().copy(exchanges = listOf(exchange(), exchange()))).single().exchanges).hasSize(1)
    }

    @Test
    fun `blank ASR cannot be an assessed answer`() {
        val transcript = turns().map { if (it.id == 2L) it.copy(transcript = "  ") else it }
        assertThat(verify(exploration(), transcript)).isEmpty()
    }

    @Test
    fun `a forged setup reply linked to an earlier question invalidates that incomplete answer set`() {
        val setupQuestion = turn(6, VoiceTutorTranscriptRole.TUTOR, "Spring 루트를 만들까요?")
        val setupAnswer = turn(7, VoiceTutorTranscriptRole.USER, "네, 만들어 주세요.")
            .copy(studyQuestionTurnId = 1)
        val setupExchange = exchange().copy(
            questionTurnId = 6, answerTurnIds = listOf(7), feedbackTurnIds = emptyList(),
            question = setupQuestion.transcript, answer = setupAnswer.transcript,
            score = null, strengths = emptyList(), improvements = emptyList(),
        )

        val result = verify(
            exploration().copy(exchanges = listOf(exchange(), setupExchange)),
            turns() + setupQuestion + setupAnswer,
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `a verified answer mixed with an unlinked setup reply rejects the whole tutor exchange`() {
        val setupReply = turn(6, VoiceTutorTranscriptRole.USER, "네, 설정해 주세요.")
        val contaminated = exchange().copy(
            answer = "메모리를 확보합니다. 네, 설정해 주세요.",
            answerTurnIds = listOf(2, 6), feedbackTurnIds = emptyList(),
            score = null, strengths = emptyList(), improvements = emptyList(),
        )

        assertThat(verify(exploration().copy(exchanges = listOf(contaminated)), turns() + setupReply)).isEmpty()
    }

    @Test
    fun `unsupported inferred score degrades to an ungraded exchange and erases its fabricated evaluation`() {
        val transcript = turns().map { if (it.id == 3L) it.copy(transcript = "캐시 응답은 85ms이고 적중률은 100%입니다.") else it }
        val result = verify(exploration(), transcript).single().exchanges.single()

        assertThat(result.question).isEqualTo(exchange().question)
        assertThat(result.answer).isEqualTo(exchange().answer)
        assertThat(result.score).isNull()
        assertThat(result.strengths).isEmpty()
        assertThat(result.improvements).isEmpty()
        assertThat(result.feedbackTurnIds).isEmpty()
    }

    @Test
    fun `unscored actual feedback is preserved without inventing a grade`() {
        val transcript = turns().map { if (it.id == 3L) it.copy(transcript = "메모리 한도를 이해했어요. 정책 차이는 더 설명해주세요.") else it }
        val result = verify(exploration().copy(exchanges = listOf(exchange().copy(score = null))), transcript).single().exchanges.single()

        assertThat(result.score).isNull()
        assertThat(result.strengths).isNotEmpty()
        assertThat(result.feedbackTurnIds).containsExactly(3L)
    }

    @Test
    fun `unanswered tutor question is not a verified learning exchange`() {
        assertThat(verify(exploration().copy(exchanges = listOf(exchange().copy(answerTurnIds = emptyList()))))).isEmpty()
    }

    @Test
    fun `learner question cannot become a learner assessment even when the model supplies grading fields`() {
        val modelMistake = exchange().copy(kind = VoiceTutorExchangeKind.LEARNER_QUESTION, questionTurnId = 4, answerTurnIds = listOf(5))
        val result = verify(exploration().copy(exchanges = listOf(modelMistake))).single().exchanges.single()

        assertThat(result.kind).isEqualTo(VoiceTutorExchangeKind.LEARNER_QUESTION)
        assertThat(result.score).isNull()
        assertThat(result.strengths).isEmpty()
        assertThat(result.improvements).isEmpty()
        assertThat(result.feedbackTurnIds).isEmpty()
    }

    @Test
    fun `setup topic and configuration questions are not learner explorations beside a valid answer`() {
        val setupQuestion = turn(6, VoiceTutorTranscriptRole.USER, "Spring 루트를 만들고 레벨을 바꿀 수 있나요?")
        val setupReply = turn(7, VoiceTutorTranscriptRole.TUTOR, "루트와 레벨 설정을 도와드릴게요.")
        val claimed = exchange().copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION,
            questionTurnId = 6,
            answerTurnIds = listOf(7),
            feedbackTurnIds = emptyList(),
            score = null,
            strengths = emptyList(),
            improvements = emptyList(),
        )

        val result = verify(exploration().copy(exchanges = listOf(exchange(), claimed)), turns() + setupQuestion + setupReply)

        assertThat(result.single().exchanges.map { it.questionTurnId }).containsExactly(1L)
    }

    @Test
    fun `attested learner question requires the complete directly following tutor answer run`() {
        val question = turn(6, VoiceTutorTranscriptRole.USER, "LFU의 시간 복잡도는 어떻게 되나요?")
            .copy(askedStudyQuestion = true)
        val firstAnswer = turn(7, VoiceTutorTranscriptRole.TUTOR, "일반적으로 빈도 갱신은 상수 시간으로 설계합니다.")
        val secondAnswer = turn(8, VoiceTutorTranscriptRole.TUTOR, "최소 빈도 그룹 추적도 함께 필요합니다.")
        val exchange = exchange().copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION,
            questionTurnId = 6,
            answerTurnIds = listOf(7, 8),
            feedbackTurnIds = emptyList(),
            score = null,
            strengths = emptyList(),
            improvements = emptyList(),
        )
        val transcript = turns() + question + firstAnswer + secondAnswer

        assertThat(verify(exploration().copy(exchanges = listOf(exchange)), transcript).single().exchanges.single().answerTurnIds)
            .containsExactly(7L, 8L)
        assertThat(verify(exploration().copy(exchanges = listOf(exchange.copy(answerTurnIds = listOf(7)))), transcript))
            .isEmpty()
    }

    @Test
    fun `tutor question requires the exact complete ordered durable multipart answer set`() {
        val transcript = listOf(
            turn(1, VoiceTutorTranscriptRole.TUTOR, "DI의 장점은 무엇인가요?")
                .copy(isStudyQuestion = true),
            turn(2, VoiceTutorTranscriptRole.USER, "결합도를 낮추고")
                .copy(studyQuestionTurnId = 1),
            turn(3, VoiceTutorTranscriptRole.USER, "음…"),
            turn(4, VoiceTutorTranscriptRole.USER, "테스트를 쉽게 합니다.")
                .copy(studyQuestionTurnId = 1),
            turn(5, VoiceTutorTranscriptRole.TUTOR, "정확합니다.")
                .copy(studyAnswerTurnId = 4),
        )
        val complete = exchange().copy(
            question = "DI의 장점은 무엇인가요?",
            answer = "결합도를 낮추고 테스트를 쉽게 합니다.",
            questionTurnId = 1,
            answerTurnIds = listOf(2, 4),
            feedbackTurnIds = listOf(5),
            score = null,
            strengths = emptyList(),
            improvements = emptyList(),
        )

        assertThat(verify(exploration().copy(exchanges = listOf(complete)), transcript)
            .single().exchanges.single().answerTurnIds).containsExactly(2L, 4L)
        listOf(listOf(2L), listOf(4L), listOf(4L, 2L), listOf(2L, 2L, 4L)).forEach { ids ->
            assertThat(verify(
                exploration().copy(exchanges = listOf(complete.copy(answerTurnIds = ids))),
                transcript,
            )).isEmpty()
        }
        val earlierPartFeedback = transcript.map { turn ->
            if (turn.id == 5L) turn.copy(studyAnswerTurnId = 2) else turn
        }
        assertThat(verify(exploration().copy(exchanges = listOf(complete)), earlierPartFeedback)).isEmpty()
    }

    @Test
    fun `unknown study identity or mismatched topic remains an unlinked discussed topic without guessed level`() {
        for (item in listOf(exploration().copy(studyId = 9_999), exploration().copy(topic = "Invented saved child"), exploration().copy(studyId = null))) {
            val result = verify(item).single()
            assertThat(result.exchanges).hasSize(1)
            assertThat(result.studyId).isNull()
            assertThat(result.difficulty).isNull()
        }
        assertThat(verify(exploration().copy(difficulty = 1)).single().difficulty).isEqualTo(7)
    }

    @Test
    fun `conflicting topic snapshots cannot supply a study link or grade level`() {
        val result = VoiceTutorExplorationEvidence.verified(listOf(exploration()), session(), turns(), listOf(snapshot, snapshot.copy(difficulty = 2)))
        assertThat(result.single().studyId).isNull()
        assertThat(result.single().difficulty).isNull()
    }

    @Test
    fun `a tutor question rejects answer and feedback arriving in another lesson revision`() {
        val revised = snapshot.copy(topic = "Renamed eviction", difficulty = 9, revision = 1)
        val transcript = turns().map { if (it.id > 1) it.copy(lessonRevision = 1) else it }

        assertThat(VoiceTutorExplorationEvidence.verified(
            listOf(exploration().copy(topic = revised.topic, difficulty = 9)),
            session(), transcript, listOf(snapshot, revised),
        )).isEmpty()
    }

    @Test
    fun `one model topic group is split by the question epoch and a new learner question uses the new level`() {
        val revised = snapshot.copy(topic = "Eviction policies", difficulty = 9, revision = 1)
        val deeper = exchange().copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION, questionTurnId = 4, answerTurnIds = listOf(5),
            feedbackTurnIds = emptyList(), score = null, strengths = emptyList(), improvements = emptyList(),
        )
        val transcript = turns().map { if (it.id >= 4) it.copy(lessonRevision = 1) else it }
        val result = VoiceTutorExplorationEvidence.verified(
            listOf(exploration().copy(topic = revised.topic, exchanges = listOf(exchange(), deeper))),
            session(), transcript, listOf(snapshot, revised),
        )

        assertThat(result.map { it.studyId }).containsExactly(43, 43)
        assertThat(result.map { it.topic }).containsExactly(snapshot.topic, revised.topic)
        assertThat(result.map { it.difficulty }).containsExactly(7, 9)
        assertThat(result.map { it.exchanges.single().questionTurnId }).containsExactly(1, 4)
        // Re-verification at durable projection must not merge groups or choose the latest level.
        assertThat(VoiceTutorExplorationEvidence.verified(result, session(), transcript, listOf(snapshot, revised))).isEqualTo(result)
    }

    @Test
    fun `same name revisions remain separate even after difficulty returns to the original value`() {
        val history = listOf(snapshot, snapshot.copy(difficulty = 9, revision = 1), snapshot.copy(revision = 2))
        val futureQuestion = exchange().copy(questionTurnId = 6, answerTurnIds = emptyList(), feedbackTurnIds = emptyList())
        val transcript = turns() + turn(6, VoiceTutorTranscriptRole.TUTOR, "다음 질문입니다.").copy(lessonRevision = 2)
        val result = VoiceTutorExplorationEvidence.verified(
            listOf(exploration().copy(exchanges = listOf(exchange(), futureQuestion))), session(), transcript, history,
        )

        assertThat(result).hasSize(1)
        assertThat(result.map { it.difficulty }).containsExactly(7)
        assertThat(result.map { it.exchanges.single().questionTurnId }).containsExactly(1)
    }

    @Test
    fun `newer ambiguous snapshot cannot silently revert a changed question to the old level`() {
        val revised = snapshot.copy(difficulty = 9, revision = 1)
        val transcript = turns().map { it.copy(lessonRevision = 1) }
        val result = VoiceTutorExplorationEvidence.verified(
            listOf(exploration()), session(), transcript, listOf(snapshot, revised, revised.copy(difficulty = 8)),
        ).single()
        assertThat(result.studyId).isNull()
        assertThat(result.difficulty).isNull()
        assertThat(result.exchanges.single().questionTurnId).isEqualTo(1)
    }

    @Test
    fun `negative question epoch retains source exchange but cannot bind any saved level`() {
        val transcript = turns().map { if (it.id <= 3L) it.copy(lessonRevision = -1) else it }
        val result = verify(exploration(), transcript).single()
        assertThat(result.studyId).isNull()
        assertThat(result.difficulty).isNull()
        assertThat(result.exchanges).hasSize(1)
    }

    @Test
    fun `a future question epoch never falls back to the latest known level`() {
        val revised = snapshot.copy(difficulty = 9, revision = 1)
        val transcript = turns().map { if (it.id <= 3L) it.copy(lessonRevision = 2) else it }
        val result = VoiceTutorExplorationEvidence.verified(listOf(exploration()), session(), transcript, listOf(snapshot, revised)).single()
        assertThat(result.studyId).isNull()
        assertThat(result.difficulty).isNull()
        assertThat(result.exchanges.single().questionTurnId).isEqualTo(1)
    }

    @Test
    fun `markup and remote links in generated learning fields are sanitized`() {
        val item = exploration().copy(
            depthSummary = "<img src='https://private.test'>LRU [LFU](https://private.test)",
            exchanges = listOf(exchange().copy(answer = "![secret](https://private.test) 메모리 한도", strengths = listOf("<b>정확함</b>"))),
        )
        val result = verify(item).single()
        assertThat(result.depthSummary).doesNotContain("https://", "<img", "](")
        assertThat(result.exchanges.single().answer).doesNotContain("https://", "![")
        assertThat(result.exchanges.single().strengths.single()).isEqualTo("정확함")
    }

    @Test
    fun `explicit zero full score and Korean English Japanese numerical feedback are supported`() {
        listOf("0점입니다." to 0, "100점이에요." to 100, "100점 만점에 85점입니다." to 85,
            "Your score is 85." to 85, "Your score is 0." to 0, "Your score is 100." to 100,
            "Your score is 85!" to 85, "Your score is 85, with good reasoning." to 85,
            "85 out of 100." to 85, "85/100" to 85, "85/100." to 85, "今回は85点です。" to 85,
            "The maximum score is 100. Your score is 85." to 85,
        ).forEach { (text, score) -> assertThat(VoiceTutorSpokenScoreEvidence.supports(text, score)).describedAs(text).isTrue() }
    }

    @Test
    fun `quantities decimal grades denominators other scales and nonexistent scores are not manufactured`() {
        listOf("85ms, 100%" to 85, "100점 만점에 85점" to 100, "만점은 100점이고 답변은 85점" to 100,
            "The maximum score is 100; your score is 85" to 100, "85.5점" to 85, "85.5점" to 5,
            "-5점" to 5, "180점" to 80, "10점 만점에 8점" to 8, "Score: 8/10" to 8,
            "Score: 85%" to 85, "Your score is 85." to 90, "満点は100点、今回は85点" to 100,
            "Your score is 85.5." to 85, "Your score is 85.5." to 5,
            "85.5 out of 100." to 85, "85/100.5" to 85, "85 out of 100.5" to 85,
            "85/1000." to 85, "Score: 8/10." to 8, "85ms." to 85,
            "The maximum score is 100. Your score is 85." to 100,
        ).forEach { (text, score) -> assertThat(VoiceTutorSpokenScoreEvidence.supports(text, score)).describedAs(text).isFalse() }
    }

    @Test
    fun `discovery navigation before the first explicit focus is excluded even when its ASR and answers arrive later`() {
        val discovery = session().copy(studyId = 43, acceptedStudyId = null, topic = snapshot.topic, difficulty = snapshot.difficulty)
        val transcript = turns().map { if (it.id >= 4) it.copy(lessonRevision = 1) else it }
        val result = VoiceTutorExplorationEvidence.verified(
            listOf(exploration()), discovery, transcript, listOf(snapshot, snapshot.copy(revision = 1)),
            focuses = listOf(VoiceTutorLessonFocus(43, 1)),
        )

        assertThat(result).isEmpty()
        assertThat(transcript.first().transcript).isEqualTo(turns().first().transcript)
    }

    @Test
    fun `questions retain their earlier explicit focus while later learner questions may move to another saved tree`() {
        val other = VoiceTutorStudySnapshot(90, null, "Message ordering", 4, revision = 2)
        val discovery = session().copy(studyId = 90, acceptedStudyId = null, topic = other.topic, difficulty = other.difficulty)
        val transcript = turns().map { it.copy(lessonRevision = if (it.id <= 3L) 1 else 2) }
        val deeper = exchange().copy(
            kind = VoiceTutorExchangeKind.LEARNER_QUESTION, questionTurnId = 4,
            answerTurnIds = listOf(5), feedbackTurnIds = emptyList(),
        )
        val result = VoiceTutorExplorationEvidence.verified(
            listOf(exploration(), exploration().copy(topic = other.topic, studyId = 90, exchanges = listOf(deeper))),
            discovery, transcript, listOf(snapshot, snapshot.copy(revision = 1), other.copy(revision = 0), other),
            focuses = listOf(VoiceTutorLessonFocus(43, 1), VoiceTutorLessonFocus(90, 2)),
        )

        assertThat(result.map { it.studyId }).containsExactly(43, 90)
        assertThat(result.map { it.difficulty }).containsExactly(7, 4)
        assertThat(result.first().exchanges.single().score).isEqualTo(85)
        assertThat(result.last().exchanges.single().score).isNull()
        assertThat(result.last().exchanges.single().feedbackTurnIds).isEmpty()
    }

    @Test
    fun `an explicit parent focus cannot authorize a child level without choosing that child`() {
        val parent = VoiceTutorStudySnapshot(42, null, "Redis", 3, revision = 1)
        val transcript = turns().map { it.copy(lessonRevision = 1) }
        val result = VoiceTutorExplorationEvidence.verified(
            listOf(exploration()), session(), transcript, listOf(snapshot, parent.copy(revision = 0), parent),
            focuses = listOf(VoiceTutorLessonFocus(42, 1)),
        ).single()

        assertThat(result.studyId).isNull()
        assertThat(result.difficulty).isNull()
        assertThat(result.exchanges.single().questionTurnId).isEqualTo(1)
    }

    private fun verify(item: VoiceTutorExploration, transcript: List<VoiceTutorTranscriptTurn> = turns()) =
        VoiceTutorExplorationEvidence.verified(listOf(item), session(), transcript, listOf(snapshot))

    private fun exploration() = VoiceTutorExploration("Redis eviction", 43, 7, "메모리 한도에서 키 제거가 필요한 이유를 설명하고 LRU와 LFU의 차이로 심화했습니다.", listOf(exchange()))

    private fun exchange() = VoiceTutorLearningExchange(
        VoiceTutorExchangeKind.TUTOR_QUESTION, "캐시는 왜 키를 제거하나요?", "메모리가 가득 찼을 때 공간을 확보하려고요.",
        85, listOf("메모리 한도를 이해함"), listOf("교체 정책 차이를 보완"), 1, listOf(2), listOf(3),
    )

    private fun turns() = listOf(
        turn(1, VoiceTutorTranscriptRole.TUTOR, "캐시는 왜 키를 제거하나요?")
            .copy(isStudyQuestion = true),
        turn(2, VoiceTutorTranscriptRole.USER, "메모리가 가득 찼을 때 공간을 확보하려고요.")
            .copy(studyQuestionTurnId = 1),
        turn(3, VoiceTutorTranscriptRole.TUTOR, "85점입니다. 메모리 한도를 이해했어요. 교체 정책 차이는 더 설명해주세요.")
            .copy(studyAnswerTurnId = 2),
        turn(4, VoiceTutorTranscriptRole.USER, "LRU와 LFU는 어떻게 달라요?")
            .copy(askedStudyQuestion = true),
        turn(5, VoiceTutorTranscriptRole.TUTOR, "최근 사용 시점과 사용 빈도의 차이입니다."),
    )

    private fun turn(id: Long, role: VoiceTutorTranscriptRole, text: String) = VoiceTutorTranscriptTurn(id, session().id, "item-$id", role, text, id, now.plusSeconds(id))

    private fun session() = VoiceTutorSession(
        id = "exploration-test", userId = 7, studyId = 42, idempotencyKey = "attempt", providerSessionId = null,
        status = VoiceTutorSessionStatus.COMPLETED, resultStatus = VoiceTutorResultStatus.PROCESSING,
        language = "ko", model = "realtime-test", voice = "marin", topic = "Redis", difficulty = 3,
        periodStartedAt = now.minusSeconds(86_400), periodEndsAt = now.plusSeconds(86_400),
        reservedSeconds = 3_600, chargedSeconds = 30, maxSessionSeconds = 3_600, hardEndsAt = now.plusSeconds(3_600),
        connectedAt = now.minusSeconds(30), relayHeartbeatAt = now, acceptedAudioBytes = 0,
        endedAt = now, finalizedAt = now, endReason = "USER_ENDED", failureCode = null, failureMessage = null,
        createdAt = now.minusSeconds(31), updatedAt = now,
    )
}
