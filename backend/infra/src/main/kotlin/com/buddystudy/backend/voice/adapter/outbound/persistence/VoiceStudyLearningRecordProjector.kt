package com.buddystudy.backend.voice.adapter.outbound.persistence

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import com.buddystudy.backend.voice.adapter.outbound.openai.VoiceTutorExplorationEvidence
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExploration
import com.buddystudy.voice.domain.VoiceTutorStudySnapshot
import com.buddystudy.voice.domain.VoiceTutorTranscriptTurn

/** Derivation only: no new questions, grades, tree nodes, topic-name matching or user-answer rewriting. */
internal object VoiceStudyLearningRecordProjector {
    fun project(
        userId: Long,
        sessionId: String,
        selectedStudyId: Long?,
        language: String,
        explorations: List<VoiceTutorExploration>,
        transcript: List<VoiceTutorTranscriptTurn>,
        snapshots: List<VoiceTutorStudySnapshot>,
        ownedStudyIds: Set<Long>,
        detectLanguage: (String, String) -> String,
    ): List<VoiceStudyLearningRecord> {
        val snapshotsById = snapshots.groupBy { it.studyId }
            .filterValues { it.distinct().size == 1 }.mapValues { it.value.first() }
        val selectedRoot = selectedStudyId?.let { rootOf(it, snapshotsById) }
        val treeStudyIds = snapshotsById.keys.filterTo(mutableSetOf()) { id ->
            selectedStudyId != null && (descendsFrom(id, selectedStudyId, snapshotsById) ||
                (selectedRoot != null && rootOf(id, snapshotsById) == selectedRoot))
        }
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
            sessionId, sourceTurns, snapshots,
        )
        val turns = sourceTurns.associateBy { it.id }
        val fallback = QuestionLanguage.normalize(language)
        return valid.flatMap { exploration ->
            val snapshot = exploration.studyId?.let(snapshotsById::get)
                ?.takeIf { it.studyId in ownedStudyIds && it.studyId in treeStudyIds }
                ?: return@flatMap emptyList()
            exploration.exchanges.mapNotNull { exchange ->
                val questionTurn = turns[exchange.questionTurnId] ?: return@mapNotNull null
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
