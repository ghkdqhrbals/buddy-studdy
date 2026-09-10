package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorLessonFocusIndex
import com.buddystudy.voice.domain.VoiceTutorSession
import com.buddystudy.voice.domain.VoiceTutorStudyRevisionIndex
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn

/** Invalid extraction evidence cannot erase a valid session summary or manufacture a grade. */
internal object VoiceTutorExplorationEvidence {
    fun verified(
        explorations: List<VoiceTutorExploration>,
        session: VoiceTutorSession,
        transcript: List<VoiceTutorTranscriptTurn>,
        studies: List<VoiceTutorStudySnapshot>,
        focuses: List<VoiceTutorLessonFocus> = emptyList(),
    ): List<VoiceTutorExploration> = verifiedForSession(
        explorations, session.id, transcript, studies, session.acceptedStudyId, focuses,
    )

    fun verifiedForSession(
        explorations: List<VoiceTutorExploration>,
        sessionId: String,
        transcript: List<VoiceTutorTranscriptTurn>,
        studies: List<VoiceTutorStudySnapshot>,
        acceptedStudyId: Long?,
        focuses: List<VoiceTutorLessonFocus> = emptyList(),
    ): List<VoiceTutorExploration> {
        // Reject ambiguous IDs, foreign-session evidence and blank ASR instead of correlating by text.
        val turns = transcript.filter { it.sessionId == sessionId && it.id > 0 && it.transcript.isNotBlank() }
            .groupBy(VoiceTutorTranscriptTurn::id)
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        val orderedTurns = turns.values.sortedWith(
            compareBy<VoiceTutorTranscriptTurn> { it.sequenceNumber }.thenBy { it.id },
        )
        val snapshots = VoiceTutorStudyRevisionIndex(studies)
        val focusIndex = VoiceTutorLessonFocusIndex(focuses, acceptedStudyId)
        val seenQuestions = mutableSetOf<Long>()
        return explorations.flatMap { exploration ->
            val exchanges = exploration.exchanges.mapNotNull { exchange ->
                verifyExchange(exchange, turns, orderedTurns)?.takeIf {
                    val revision = requireNotNull(turns[it.questionTurnId]).lessonRevision
                    // Unfocused discovery/navigation is not a saved lesson. Unknown correlation
                    // still survives as unlinked session history, never as a node record.
                    (revision < 0 || focusIndex.at(revision) != null) && seenQuestions.add(it.questionTurnId)
                }
            }
            if (exchanges.isEmpty()) return@flatMap emptyList()
            val topic = plain(exploration.topic)
            val depth = plain(exploration.depthSummary)
            if (topic.isBlank() || depth.isBlank()) return@flatMap emptyList()
            // A model may collect two levels of the same saved node into one topic group. Validate
            // the identity/name against captured metadata, then let EACH QUESTION's server epoch
            // choose the version. A later answer, feedback, rename or ASR arrival is not an epoch.
            val knownStudyId = exploration.studyId?.takeIf { id ->
                snapshots.knownVersions(id).any { plain(it.topic) == topic }
            }
            exchanges.groupBy { exchange ->
                val revision = requireNotNull(turns[exchange.questionTurnId]).lessonRevision
                val focus = focusIndex.at(revision)
                knownStudyId?.takeIf { focus != null && (focus.revision == 0L || focus.studyId == it) }
                    ?.let { snapshots.resolve(it, revision) }
            }.map { (snapshot, versionExchanges) ->
                exploration.copy(
                    topic = snapshot?.let { plain(it.topic) } ?: topic,
                    // Unsaved/uncertain identity remains in the session but never invents a node level.
                    studyId = snapshot?.studyId,
                    difficulty = snapshot?.difficulty,
                    depthSummary = depth,
                    exchanges = versionExchanges,
                )
            }
        }
    }

    private fun verifyExchange(
        exchange: VoiceTutorLearningExchange,
        turns: Map<Long, VoiceTutorTranscriptTurn>,
        orderedTurns: List<VoiceTutorTranscriptTurn>,
    ): VoiceTutorLearningExchange? {
        val questionTurn = turns[exchange.questionTurnId] ?: return null
        val questionRole = when (exchange.kind) {
            VoiceTutorExchangeKind.TUTOR_QUESTION -> VoiceTutorTranscriptRole.TUTOR
            VoiceTutorExchangeKind.LEARNER_QUESTION -> VoiceTutorTranscriptRole.USER
        }
        val answerRole = if (questionRole == VoiceTutorTranscriptRole.TUTOR) VoiceTutorTranscriptRole.USER else VoiceTutorTranscriptRole.TUTOR
        if (questionTurn.interrupted || questionTurn.role != questionRole) return null
        if (exchange.kind == VoiceTutorExchangeKind.TUTOR_QUESTION && !questionTurn.isStudyQuestion) return null
        val question = plain(exchange.question).takeIf(String::isNotBlank) ?: return null
        val answers = orderedEvidence(exchange.answerTurnIds, turns, answerRole, questionTurn.sequenceNumber) ?: return null
        val answer = plain(exchange.answer)
        if (exchange.kind == VoiceTutorExchangeKind.TUTOR_QUESTION) {
            val completeDurableAnswer = orderedTurns.filter { turn ->
                turn.role == VoiceTutorTranscriptRole.USER &&
                    turn.studyQuestionTurnId == questionTurn.id
            }
            if (
                answers.map(VoiceTutorTranscriptTurn::id) !=
                    completeDurableAnswer.map(VoiceTutorTranscriptTurn::id) ||
                answers.isEmpty() || answers.any {
                    it.studyQuestionTurnId != questionTurn.id ||
                        it.lessonRevision != questionTurn.lessonRevision ||
                        hasInterveningTutor(questionTurn, it, orderedTurns)
                }
            ) {
                // Lesson-level eligibility is insufficient: every answer row used by an assessed
                // tutor question needs a server-persisted semantic link to this exact question row.
                // Exact equality also prevents the model from grading only a convenient prefix,
                // suffix, duplicate or reordered subset of one durable multipart answer.
                return null
            }
        }
        if (exchange.kind == VoiceTutorExchangeKind.LEARNER_QUESTION &&
            (!questionTurn.askedStudyQuestion || answers.isEmpty())
        ) return null
        if (answers.isEmpty()) {
            // Do not turn a later unrelated utterance or a model-completed answer into learner performance.
            return exchange.copy(question = question, answer = "", score = null, strengths = emptyList(), improvements = emptyList(), feedbackTurnIds = emptyList())
        }
        if (answer.isBlank()) return null
        if (exchange.kind == VoiceTutorExchangeKind.LEARNER_QUESTION) {
            val directTutorAnswerRun = directTutorAnswerRun(questionTurn, orderedTurns) ?: return null
            if (answers.map { it.id } != directTutorAnswerRun.map { it.id }) return null
            return exchange.copy(question = question, answer = answer, score = null, strengths = emptyList(), improvements = emptyList(), feedbackTurnIds = emptyList())
        }
        val feedback = orderedEvidence(exchange.feedbackTurnIds, turns, VoiceTutorTranscriptRole.TUTOR, answers.last().sequenceNumber)
            ?: return null
        if (feedback.isEmpty()) {
            return exchange.copy(question = question, answer = answer, score = null, strengths = emptyList(), improvements = emptyList())
        }
        val exactAnswer = answers.last()
        if (feedback.any {
                it.studyAnswerTurnId != exactAnswer.id || it.lessonRevision != exactAnswer.lessonRevision ||
                    it.isStudyQuestion || it.studyQuestionTurnId != null || it.askedStudyQuestion ||
                    hasInterveningTutor(exactAnswer, it, orderedTurns)
            }
        ) return null
        val score = exchange.score
        val scoreSupported = score == null || feedback.any { VoiceTutorSpokenScoreEvidence.supports(it.transcript, score) }
        return exchange.copy(
            question = question,
            answer = answer,
            score = exchange.score.takeIf { scoreSupported },
            strengths = if (scoreSupported) exchange.strengths.map(::plain).filter(String::isNotBlank) else emptyList(),
            improvements = if (scoreSupported) exchange.improvements.map(::plain).filter(String::isNotBlank) else emptyList(),
            feedbackTurnIds = if (scoreSupported) exchange.feedbackTurnIds else emptyList(),
        )
    }

    private fun orderedEvidence(
        ids: List<Long>,
        turns: Map<Long, VoiceTutorTranscriptTurn>,
        role: VoiceTutorTranscriptRole,
        afterSequence: Long,
    ): List<VoiceTutorTranscriptTurn>? {
        if (ids.distinct().size != ids.size) return null
        var previous = afterSequence
        return ids.map { id ->
            val turn = turns[id] ?: return null
            if (turn.interrupted || turn.role != role || turn.sequenceNumber <= previous) return null
            previous = turn.sequenceNumber
            turn
        }
    }

    /**
     * Transcript inserts are serialized by the locked session row and the sideband controller persists a
     * completed tutor batch before releasing later learner work. The full consecutive TUTOR run is therefore
     * the only response that can belong to this attested learner question; a gap, another USER turn, a focus
     * revision change or ambiguous sequence fails closed.
     */
    private fun directTutorAnswerRun(
        question: VoiceTutorTranscriptTurn,
        orderedTurns: List<VoiceTutorTranscriptTurn>,
    ): List<VoiceTutorTranscriptTurn>? {
        if (orderedTurns.groupingBy { it.sequenceNumber }.eachCount().any { it.value != 1 }) return null
        val following = orderedTurns.dropWhile { it.sequenceNumber <= question.sequenceNumber }
        val first = following.firstOrNull() ?: return null
        if (first.sequenceNumber != question.sequenceNumber + 1 ||
            first.role != VoiceTutorTranscriptRole.TUTOR ||
            first.lessonRevision != question.lessonRevision
        ) return null
        val run = following.takeWhile {
            it.role == VoiceTutorTranscriptRole.TUTOR && it.lessonRevision == question.lessonRevision
        }
        if (run.isEmpty() || run.any { it.interrupted } || run.withIndex().any { (index, turn) ->
                turn.sequenceNumber != question.sequenceNumber + index + 1
            }
        ) return null
        return run
    }

    private fun hasInterveningTutor(
        before: VoiceTutorTranscriptTurn,
        after: VoiceTutorTranscriptTurn,
        orderedTurns: List<VoiceTutorTranscriptTurn>,
    ): Boolean = orderedTurns.any { turn ->
        turn.role == VoiceTutorTranscriptRole.TUTOR &&
            turn.sequenceNumber > before.sequenceNumber && turn.sequenceNumber < after.sequenceNumber
    }

    private fun plain(value: String) = VoiceTutorLearningResultSanitizer.plainText(value)
}

/** Numeric provenance, not a classifier: the summary still decides whether feedback is about this answer. */
internal object VoiceTutorSpokenScoreEvidence {
    fun supports(feedback: String, score: Int): Boolean {
        if (score !in 0..100) return false
        if (nonHundredPointScale.containsMatchIn(feedback)) return false
        val denominators = (scaleDescriptions + otherFractionScale).flatMap { it.findAll(feedback).map(MatchResult::range).toList() }
        return scoreExpressions.any { expression ->
            expression.findAll(feedback).any { match ->
                match.groupValues[1].toIntOrNull() == score && denominators.none { range ->
                    range.first <= match.range.last && range.last >= match.range.first
                }
            }
        }
    }

    // Require an explicit points/score scale. Bare quantities, latency, percentages and a 100-point
    // denominator are not grades. This does not filter learner speech, words, or hesitation.
    private val scoreExpressions = listOf(
        Regex("(?<![\\d.−-])(100|[1-9]?\\d)\\s*점(?!\\s*(?:만점|중|척도|기준|짜리))"),
        Regex("(?<![\\d.−-])(100|[1-9]?\\d)\\s*点(?!\\s*満点)"),
        Regex("(?i)(?<![\\d.−-])(100|[1-9]?\\d)\\s*(?:/\\s*100|out\\s+of\\s+100)(?!\\d|\\.\\d)"),
        Regex("(?i)\\b(?:score|grade)\\s*(?:(?:is|of|was)\\s*)?[:=]?\\s*(100|[1-9]?\\d)(?!\\d|\\.\\d|\\s*(?:%|percent\\b))"),
    )
    private val scaleDescriptions = listOf(
        Regex("(?:만점|최고점|총점|배점)(?:은|는|이|가)?\\s*[:：]?\\s*(?:100|[1-9]?\\d)\\s*점"),
        Regex("満点(?:は|が)?\\s*[:：]?\\s*(?:100|[1-9]?\\d)\\s*点"),
        Regex("(?i)\\b(?:maximum|full|total)\\s+(?:possible\\s+)?(?:score|grade)\\s*(?:(?:is|of|was)\\s*)?[:=]?\\s*(?:100|[1-9]?\\d)(?!\\d|\\.\\d)"),
    )
    private val nonHundredPointScale = Regex("(?<![\\d.])(?:[1-9]?\\d)\\s*(?:점\\s*만점|点\\s*満点)")
    private val otherFractionScale = Regex("(?i)(?<![\\d.])(?:100|[1-9]?\\d)\\s*(?:/|out\\s+of)\\s*(?!100(?!\\d|\\.\\d))\\d+(?:\\.\\d+)?")
}
