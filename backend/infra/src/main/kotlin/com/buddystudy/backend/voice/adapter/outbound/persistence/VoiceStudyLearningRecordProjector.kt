package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorExplorationEvidence
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorLessonFocus
import com.buddystudy.voice.domain.VoiceTutorLessonFocusIndex
import com.buddystudy.voice.domain.VoiceTutorStudyRevisionIndex
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn

/** Derivation only: no new questions, grades, tree nodes, topic-name matching or user-answer rewriting. */
internal object VoiceStudyLearningRecordProjector {
    fun project(
        userId: Long,
        sessionId: String,
        acceptedStudyId: Long?,
        language: String,
        explorations: List<VoiceTutorExploration>,
        transcript: List<VoiceTutorTranscriptTurn>,
        snapshots: List<VoiceTutorStudySnapshot>,
        ownedStudyIds: Set<Long>,
        detectLanguage: (String, String) -> String,
        focuses: List<VoiceTutorLessonFocus> = emptyList(),
    ): List<VoiceStudyLearningRecord> {
        val revisions = VoiceTutorStudyRevisionIndex(snapshots)
        val focusIndex = VoiceTutorLessonFocusIndex(focuses, acceptedStudyId)
        val epochViews = mutableMapOf<Long, Map<Long, VoiceTutorStudySnapshot>>()
        // An ambiguous cross-node question remains available in the session, never arbitrarily filed.
        val unambiguousQuestionIds = explorations.flatMap { exploration ->
            exploration.exchanges.map { it.questionTurnId to exploration.studyId }
        }.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size == 1 }.keys
        // Verification and source reconstruction must use the exact same unambiguous turns.
        val sourceTurns = transcript.filter { it.sessionId == sessionId }.groupBy { it.id }
            .filterValues { it.size == 1 }.values.map { it.single() }
            .filter { it.id > 0 && it.transcript.isNotBlank() }
        val valid = VoiceTutorExplorationEvidence.verifiedForSession(
            explorations.map { it.copy(exchanges = it.exchanges.filter { e -> e.questionTurnId in unambiguousQuestionIds }) },
            sessionId, sourceTurns, snapshots, acceptedStudyId, focuses,
        )
        val turns = sourceTurns.associateBy { it.id }
        val fallback = QuestionLanguage.normalize(language)
        return valid.flatMap { exploration ->
            val studyId = exploration.studyId?.takeIf { it in ownedStudyIds } ?: return@flatMap emptyList()
            exploration.exchanges.mapNotNull { exchange ->
                val questionTurn = turns[exchange.questionTurnId] ?: return@mapNotNull null
                val snapshotsById = epochViews.getOrPut(questionTurn.lessonRevision) { revisions.viewAt(questionTurn.lessonRevision) }
                val snapshot = snapshotsById[studyId] ?: return@mapNotNull null
                val focus = focusIndex.at(questionTurn.lessonRevision) ?: return@mapNotNull null
                // Explicit selection applies to exactly this node at question time, never the
                // session's final focus. Original revision-zero calls retain their tree scope.
                if (focus.revision > 0) {
                    if (focus.studyId != studyId) return@mapNotNull null
                } else {
                    val root = rootOf(focus.studyId, snapshotsById)
                    if (!(descendsFrom(studyId, focus.studyId, snapshotsById) ||
                            (root != null && rootOf(studyId, snapshotsById) == root))
                    ) return@mapNotNull null
                }
                val answer = exchange.answerTurnIds.mapNotNull(turns::get)
                    .joinToString("\n") { it.transcript }.takeIf(String::isNotBlank)
                val feedback = exchange.feedbackTurnIds.mapNotNull(turns::get)
                    .joinToString("\n") { it.transcript }.takeIf(String::isNotBlank)
                // Keep original source turns intact. Oversized evidence stays in the source session.
                if (questionTurn.transcript.length > MAX_TEXT_CHARACTERS ||
                    (answer?.length ?: 0) > MAX_TEXT_CHARACTERS ||
                    (feedback?.length ?: 0) > MAX_TEXT_CHARACTERS
                ) return@mapNotNull null
                val draft = VoiceStudyLearningRecord(
                    id = 0, userId = userId, sessionId = sessionId,
                    studyId = snapshot.studyId, parentStudyId = snapshot.parentStudyId,
                    topic = snapshot.topic, difficulty = snapshot.difficulty,
                    createdAt = questionTurn.occurredAt, kind = exchange.kind,
                    question = questionTurn.transcript, answer = answer, score = exchange.score,
                    strengths = exchange.strengths, improvements = exchange.improvements,
                    depthSummary = exploration.depthSummary, feedback = feedback,
                    questionTurnId = exchange.questionTurnId,
                    answerTurnIds = exchange.answerTurnIds, feedbackTurnIds = exchange.feedbackTurnIds,
                    sourceLanguage = fallback, sourceLanguages = emptyMap(), sourceHash = "",
                )
                val fields = draft.translatableFields()
                val languages = fields.mapValues { (_, value) ->
                    QuestionLanguage.normalize(detectLanguage(value.orEmpty(), fallback))
                }
                val sourceHash = ContentSourceHashPolicy.sha256(
                    JsonMapperProvider.mapper.writeValueAsString(linkedMapOf("fields" to fields, "languages" to languages)),
                )
                draft.copy(sourceLanguage = languages["question"] ?: fallback, sourceLanguages = languages, sourceHash = sourceHash)
            }
        }.take(48)
    }

    private fun rootOf(id: Long, snapshots: Map<Long, VoiceTutorStudySnapshot>): Long? {
        val seen = mutableSetOf<Long>()
        var cursor = id
        repeat(64) {
            if (!seen.add(cursor)) return null
            val node = snapshots[cursor] ?: return null
            cursor = node.parentStudyId ?: return node.studyId
        }
        return null
    }

    private fun descendsFrom(id: Long, ancestor: Long, snapshots: Map<Long, VoiceTutorStudySnapshot>): Boolean {
        val seen = mutableSetOf<Long>()
        var cursor = id
        repeat(64) {
            if (!seen.add(cursor)) return false
            if (cursor == ancestor) return true
            cursor = snapshots[cursor]?.parentStudyId ?: return false
        }
        return false
    }

    private const val MAX_TEXT_CHARACTERS = 16_000
}
