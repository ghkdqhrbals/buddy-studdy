package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLearningExchange
import com.buddystudy.voice.domain.VoiceTutorSession
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
    ): List<VoiceTutorExploration> = verifiedForSession(explorations, session.id, transcript, studies)

    fun verifiedForSession(
        explorations: List<VoiceTutorExploration>,
        sessionId: String,
        transcript: List<VoiceTutorTranscriptTurn>,
        studies: List<VoiceTutorStudySnapshot>,
    ): List<VoiceTutorExploration> {
        // Reject ambiguous IDs, foreign-session evidence and blank ASR instead of correlating by text.
        val turns = transcript.filter { it.sessionId == sessionId && it.id > 0 && it.transcript.isNotBlank() }
            .groupBy(VoiceTutorTranscriptTurn::id)
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        val snapshots = studies.filter { it.studyId > 0 && it.difficulty in 1..10 && it.topic.isNotBlank() }
            .groupBy(VoiceTutorStudySnapshot::studyId)
            .filterValues { it.distinct().size == 1 }
            .mapValues { it.value.first() }
        val seenQuestions = mutableSetOf<Long>()
        return explorations.mapNotNull { exploration ->
            val exchanges = exploration.exchanges.mapNotNull { exchange ->
                verifyExchange(exchange, turns)?.takeIf { seenQuestions.add(it.questionTurnId) }
            }
            if (exchanges.isEmpty()) return@mapNotNull null
            val topic = plain(exploration.topic)
            val depth = plain(exploration.depthSummary)
            if (topic.isBlank() || depth.isBlank()) return@mapNotNull null
            val snapshot = exploration.studyId?.let(snapshots::get)
                ?.takeIf { plain(it.topic) == topic }
            exploration.copy(
                topic = topic,
                // A discussed but unsaved topic is valid; a guessed study identity/level is not.
                studyId = snapshot?.studyId,
                difficulty = snapshot?.difficulty,
                depthSummary = depth,
                exchanges = exchanges,
            )
        }
    }

    private fun verifyExchange(
        exchange: VoiceTutorLearningExchange,
        turns: Map<Long, VoiceTutorTranscriptTurn>,
    ): VoiceTutorLearningExchange? {
        val questionTurn = turns[exchange.questionTurnId] ?: return null
        val questionRole = when (exchange.kind) {
            VoiceTutorExchangeKind.TUTOR_QUESTION -> VoiceTutorTranscriptRole.TUTOR
            VoiceTutorExchangeKind.LEARNER_QUESTION -> VoiceTutorTranscriptRole.USER
        }
        val answerRole = if (questionRole == VoiceTutorTranscriptRole.TUTOR) VoiceTutorTranscriptRole.USER else VoiceTutorTranscriptRole.TUTOR
        if (questionTurn.role != questionRole) return null
        val question = plain(exchange.question).takeIf(String::isNotBlank) ?: return null
        val answers = orderedEvidence(exchange.answerTurnIds, turns, answerRole, questionTurn.sequenceNumber) ?: return null
        val answer = plain(exchange.answer)
        if (answers.isEmpty()) {
            // Do not turn a later unrelated utterance or a model-completed answer into learner performance.
            return exchange.copy(question = question, answer = "", score = null, strengths = emptyList(), improvements = emptyList(), feedbackTurnIds = emptyList())
        }
        if (answer.isBlank()) return null
        if (exchange.kind == VoiceTutorExchangeKind.LEARNER_QUESTION) {
            return exchange.copy(question = question, answer = answer, score = null, strengths = emptyList(), improvements = emptyList(), feedbackTurnIds = emptyList())
        }
        val feedback = orderedEvidence(exchange.feedbackTurnIds, turns, VoiceTutorTranscriptRole.TUTOR, answers.last().sequenceNumber)
            ?: return null
        if (feedback.isEmpty()) {
            return exchange.copy(question = question, answer = answer, score = null, strengths = emptyList(), improvements = emptyList())
        }
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
            if (turn.role != role || turn.sequenceNumber <= previous) return null
            previous = turn.sequenceNumber
            turn
        }
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
