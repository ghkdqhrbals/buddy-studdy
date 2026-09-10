package com.buddystudy.backend.voice.adapter.outbound.openai

import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorLessonFocusIndex
import com.buddystudy.voice.domain.VoiceTutorTranscriptRole
import com.buddystudy.voice.domain.VoiceTutorTranscriptSource
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn

/**
 * Projects the durable transcript down to server-attested learning exchanges before it reaches GPT.
 * Setup, navigation and configuration speech has no attestation and therefore cannot influence even the
 * global prose summary. The caller still verifies GPT's cited exchanges independently after generation.
 */
internal object VoiceTutorSummaryTranscriptEvidence {
    fun verified(
        sessionId: String,
        acceptedStudyId: Long?,
        transcript: List<VoiceTutorTranscriptTurn>,
        focuses: List<VoiceTutorLessonFocus>,
    ): List<VoiceTutorTranscriptTurn> {
        val sessionTurns = transcript.asSequence()
            .filter { it.sessionId == sessionId && it.id > 0 }
            .take(2_000)
            .toList()
        if (sessionTurns.isEmpty() ||
            sessionTurns.any { it.sequenceNumber <= 0 } ||
            sessionTurns.groupingBy { it.id }.eachCount().any { it.value != 1 } ||
            sessionTurns.groupingBy { it.sequenceNumber }.eachCount().any { it.value != 1 }
        ) return emptyList()
        val ordered = sessionTurns.sortedWith(
            compareBy<VoiceTutorTranscriptTurn> { it.sequenceNumber }.thenBy { it.id },
        )
        val turns = ordered.associateBy(VoiceTutorTranscriptTurn::id)
        val linkedAnswers = ordered.filter { it.studyQuestionTurnId != null }
            .groupBy { requireNotNull(it.studyQuestionTurnId) }
        val focusIndex = VoiceTutorLessonFocusIndex(focuses, acceptedStudyId)
        val selectedIds = linkedSetOf<Long>()
        var hasTutorQuestionExchange = false

        ordered.filter { it.role == VoiceTutorTranscriptRole.TUTOR }.forEach { question ->
            val linked = linkedAnswers[question.id].orEmpty()
            if (linked.isEmpty() || !question.isStudyQuestion || question.source != VoiceTutorTranscriptSource.AUDIO || question.transcript.isBlank() ||
                question.studyQuestionTurnId != null ||
                question.askedStudyQuestion || focusIndex.at(question.lessonRevision) == null ||
                linked.any { answer ->
                    answer.role != VoiceTutorTranscriptRole.USER || answer.source != VoiceTutorTranscriptSource.AUDIO || answer.transcript.isBlank() ||
                        answer.lessonRevision != question.lessonRevision ||
                        answer.sequenceNumber <= question.sequenceNumber || answer.askedStudyQuestion ||
                        answer.studyAnswerTurnId != null || hasInterveningTutor(question, answer, ordered)
                }
            ) return@forEach
            hasTutorQuestionExchange = true
            selectedIds += question.id
            selectedIds += linked.map(VoiceTutorTranscriptTurn::id)
            linked.maxByOrNull(VoiceTutorTranscriptTurn::sequenceNumber)?.let { finalAnswer ->
                selectedIds += exactFeedbackFor(finalAnswer, ordered).map(VoiceTutorTranscriptTurn::id)
            }
        }

        // A learner question is supplemental learning evidence, not a way for a setup-only session to
        // bypass the strict persisted TUTOR_QUESTION -> USER-answer eligibility gate.
        if (!hasTutorQuestionExchange) return emptyList()
        ordered.filter { question ->
            question.role == VoiceTutorTranscriptRole.USER && question.source == VoiceTutorTranscriptSource.AUDIO && question.askedStudyQuestion &&
                question.studyQuestionTurnId == null && question.transcript.isNotBlank() &&
                focusIndex.at(question.lessonRevision) != null
        }.forEach { question ->
            val answers = directTutorRunAfter(question, ordered)
            if (answers.isNotEmpty()) {
                selectedIds += question.id
                selectedIds += answers.map(VoiceTutorTranscriptTurn::id)
            }
        }

        return selectedIds.mapNotNull(turns::get).sortedWith(
            compareBy<VoiceTutorTranscriptTurn> { it.sequenceNumber }.thenBy { it.id },
        )
    }

    /**
     * Applies a character budget only between complete attested exchanges. A
     * multipart answer and its exact feedback are one component; cutting inside
     * it must never make a model-visible prefix look like the complete answer.
     */
    fun completeExchangePrefix(
        evidence: List<VoiceTutorTranscriptTurn>,
        maxCharacters: Int,
    ): List<VoiceTutorTranscriptTurn> {
        if (evidence.isEmpty() || maxCharacters <= 0) return emptyList()
        val ordered = evidence.sortedWith(
            compareBy<VoiceTutorTranscriptTurn> { it.sequenceNumber }.thenBy { it.id },
        )
        if (ordered.groupingBy { it.id }.eachCount().any { it.value != 1 }) return emptyList()
        val parent = ordered.associate { it.id to it.id }.toMutableMap()
        fun root(id: Long): Long {
            var current = id
            while (parent[current] != current) current = parent.getValue(current)
            var compress = id
            while (parent[compress] != current) {
                val next = parent.getValue(compress)
                parent[compress] = current
                compress = next
            }
            return current
        }
        fun union(left: Long, right: Long) {
            val leftRoot = root(left)
            val rightRoot = root(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }
        val byId = ordered.associateBy(VoiceTutorTranscriptTurn::id)
        ordered.forEach { turn ->
            turn.studyQuestionTurnId?.takeIf(byId::containsKey)?.let { union(it, turn.id) }
            turn.studyAnswerTurnId?.takeIf(byId::containsKey)?.let { union(it, turn.id) }
        }
        ordered.filter {
            it.role == VoiceTutorTranscriptRole.USER && it.askedStudyQuestion
        }.forEach { learnerQuestion ->
            val directRun = ordered.dropWhile { it.sequenceNumber <= learnerQuestion.sequenceNumber }
                .takeWhile { turn ->
                    turn.role == VoiceTutorTranscriptRole.TUTOR &&
                        turn.lessonRevision == learnerQuestion.lessonRevision
                }
            directRun.forEach { union(learnerQuestion.id, it.id) }
        }
        val groups = ordered.groupBy { root(it.id) }.values.sortedBy { group ->
            group.minOf(VoiceTutorTranscriptTurn::sequenceNumber)
        }
        var remaining = maxCharacters
        val selected = mutableListOf<VoiceTutorTranscriptTurn>()
        for (group in groups) {
            val characters = group.sumOf { it.transcript.length }
            if (characters > remaining) break
            selected += group
            remaining -= characters
        }
        return selected.sortedWith(
            compareBy<VoiceTutorTranscriptTurn> { it.sequenceNumber }.thenBy { it.id },
        )
    }

    private fun directTutorRunAfter(
        source: VoiceTutorTranscriptTurn,
        ordered: List<VoiceTutorTranscriptTurn>,
    ): List<VoiceTutorTranscriptTurn> {
        val following = ordered.dropWhile { it.sequenceNumber <= source.sequenceNumber }
        val first = following.firstOrNull() ?: return emptyList()
        if (first.sequenceNumber != source.sequenceNumber + 1 ||
            first.role != VoiceTutorTranscriptRole.TUTOR ||
            first.lessonRevision != source.lessonRevision || first.transcript.isBlank()
        ) return emptyList()
        val run = following.takeWhile {
            it.role == VoiceTutorTranscriptRole.TUTOR && it.source == VoiceTutorTranscriptSource.AUDIO && it.lessonRevision == source.lessonRevision &&
                it.transcript.isNotBlank()
        }
        return run.takeIf { turns ->
            turns.withIndex().all { (index, turn) ->
                turn.sequenceNumber == source.sequenceNumber + index + 1
            }
        }.orEmpty()
    }

    private fun exactFeedbackFor(
        answer: VoiceTutorTranscriptTurn,
        ordered: List<VoiceTutorTranscriptTurn>,
    ): List<VoiceTutorTranscriptTurn> = ordered.filter { feedback ->
        feedback.role == VoiceTutorTranscriptRole.TUTOR && feedback.source == VoiceTutorTranscriptSource.AUDIO &&
            feedback.studyAnswerTurnId == answer.id && feedback.transcript.isNotBlank() &&
            feedback.lessonRevision == answer.lessonRevision &&
            feedback.sequenceNumber > answer.sequenceNumber && !feedback.isStudyQuestion &&
            feedback.studyQuestionTurnId == null && !feedback.askedStudyQuestion &&
            !hasInterveningTutor(answer, feedback, ordered)
    }

    private fun hasInterveningTutor(
        before: VoiceTutorTranscriptTurn,
        after: VoiceTutorTranscriptTurn,
        ordered: List<VoiceTutorTranscriptTurn>,
    ): Boolean = ordered.any { turn ->
        turn.role == VoiceTutorTranscriptRole.TUTOR &&
            turn.sequenceNumber > before.sequenceNumber && turn.sequenceNumber < after.sequenceNumber
    }
}
