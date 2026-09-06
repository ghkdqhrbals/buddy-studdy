package com.buddystudy.voice.domain

import java.nio.ByteBuffer
import java.security.MessageDigest

/** A semantic post-call verdict names source IDs only, never replacement transcript or a new grade. */
data class VoiceTutorPostCallExchange(
    val questionTurnId: Long,
    val answerTurnIds: List<Long>,
    val feedbackTurnId: Long? = null,
)

data class VoiceTutorPostCallLearnerQuestion(
    val questionTurnId: Long,
    val answerTurnIds: List<Long>,
)

/** Kept server-private and committed only with the matching completed-result lease. */
data class VoiceTutorPostCallEvidence(
    val sourceHash: String,
    val exchanges: List<VoiceTutorPostCallExchange>,
    val learnerQuestions: List<VoiceTutorPostCallLearnerQuestion> = emptyList(),
) {
    /**
     * Semantic detection belongs to the post-call model. This boundary independently checks
     * exact ownership, clean source, complete role runs, focus epochs and non-overlapping IDs.
     * A stale, partial, fabricated or mixed legacy/raw verdict cannot modify durable evidence.
     */
    fun attestedTranscript(
        sessionId: String,
        transcript: List<VoiceTutorTranscriptTurn>,
        focuses: List<VoiceTutorLessonFocus>,
        acceptedStudyId: Long? = null,
    ): List<VoiceTutorTranscriptTurn>? {
        if (exchanges.size > 64 || learnerQuestions.size > 64 ||
            sourceHash != sourceHash(sessionId, transcript, focuses, acceptedStudyId) ||
            transcript.size > 2_000 || transcript.any { it.sessionId != sessionId || it.id <= 0 || it.sequenceNumber <= 0 } ||
            transcript.map { it.id }.distinct().size != transcript.size ||
            transcript.map { it.sequenceNumber }.distinct().size != transcript.size ||
            transcript.map { it.providerItemId }.distinct().size != transcript.size
        ) return null
        if (exchanges.isEmpty()) return transcript.takeIf { learnerQuestions.isEmpty() }
        val ordered = transcript.sortedBy { it.sequenceNumber }
        val byId = ordered.associateBy { it.id }
        val focus = VoiceTutorLessonFocusIndex(focuses, acceptedStudyId)
        val replacements = mutableMapOf<Long, VoiceTutorTranscriptTurn>()
        val consumed = mutableSetOf<Long>()
        fun clean(turn: VoiceTutorTranscriptTurn) = turn.postCallEvidence && turn.transcript.isNotBlank() &&
            turn.lessonRevision >= 0 && focus.at(turn.lessonRevision) != null &&
            !turn.isStudyQuestion && !turn.askedStudyQuestion &&
            turn.studyQuestionTurnId == null && turn.studyAnswerTurnId == null
        fun nextRun(question: VoiceTutorTranscriptTurn, role: VoiceTutorTranscriptRole) =
            ordered.dropWhile { it.sequenceNumber <= question.sequenceNumber }.takeWhile { it.role == role }
        for (exchange in exchanges) {
            val question = byId[exchange.questionTurnId] ?: return null
            if (!clean(question) || question.role != VoiceTutorTranscriptRole.TUTOR ||
                exchange.answerTurnIds.size !in 1..32 || !consumed.add(question.id)
            ) return null
            val answers = nextRun(question, VoiceTutorTranscriptRole.USER)
            if (answers.map { it.id } != exchange.answerTurnIds || answers.any {
                    !clean(it) || it.lessonRevision != question.lessonRevision || !consumed.add(it.id)
                }
            ) return null
            replacements[question.id] = question.copy(isStudyQuestion = true)
            answers.forEach { replacements[it.id] = it.copy(studyQuestionTurnId = question.id) }
            exchange.feedbackTurnId?.let { feedbackId ->
                val feedback = ordered.firstOrNull { it.sequenceNumber > answers.last().sequenceNumber }
                    ?: return null
                if (feedback.id != feedbackId || feedback.role != VoiceTutorTranscriptRole.TUTOR ||
                    !clean(feedback) || feedback.lessonRevision != question.lessonRevision || !consumed.add(feedbackId)
                ) return null
                replacements[feedbackId] = feedback.copy(studyAnswerTurnId = answers.last().id)
            }
        }
        for (exchange in learnerQuestions) {
            val question = byId[exchange.questionTurnId] ?: return null
            if (!clean(question) || question.role != VoiceTutorTranscriptRole.USER ||
                exchange.answerTurnIds.size !in 1..32 || !consumed.add(question.id)
            ) return null
            val answers = nextRun(question, VoiceTutorTranscriptRole.TUTOR)
            if (answers.map { it.id } != exchange.answerTurnIds || answers.any {
                    !clean(it) || it.lessonRevision != question.lessonRevision || !consumed.add(it.id)
                }
            ) return null
            replacements[question.id] = question.copy(askedStudyQuestion = true)
        }
        return ordered.map { replacements[it.id] ?: it }
    }

    companion object {
        /** Length-prefixed bytes prevent delimiter ambiguity; every raw source row is fenced. */
        fun sourceHash(
            sessionId: String,
            transcript: List<VoiceTutorTranscriptTurn>,
            focuses: List<VoiceTutorLessonFocus>,
            acceptedStudyId: Long? = null,
        ): String {
            val hash = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                hash.update(bytes)
            }
            field(sessionId)
            field(acceptedStudyId?.toString().orEmpty())
            focuses.sortedWith(compareBy<VoiceTutorLessonFocus> { it.revision }.thenBy { it.studyId }).forEach {
                field(it.studyId.toString()); field(it.revision.toString())
            }
            transcript.sortedBy { it.sequenceNumber }.forEach {
                field(it.id.toString()); field(it.sessionId); field(it.providerItemId); field(it.role.name)
                field(it.transcript); field(it.sequenceNumber.toString()); field(it.lessonRevision.toString())
                field(it.occurredAt.toString())
                field(it.postCallEvidence.toString()); field(it.isStudyQuestion.toString())
                field(it.askedStudyQuestion.toString()); field(it.studyQuestionTurnId?.toString().orEmpty())
                field(it.studyAnswerTurnId?.toString().orEmpty())
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
