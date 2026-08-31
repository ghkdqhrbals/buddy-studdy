package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.permission.Permissions
import com.buddystudy.backend.auth.application.permission.RequirePermission
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.study.application.model.StudyLearningRecordResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordsPageResponse
import com.buddystudy.backend.study.application.model.StudyLearningRecordScope
import com.buddystudy.backend.study.application.model.StudyLearningRecordSource
import com.buddystudy.backend.study.application.model.VoiceStudyLearningRecordResponse
import com.buddystudy.backend.study.application.policy.StudyLearningRecordsCursorPolicy
import com.buddystudy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystudy.backend.study.application.port.inbound.BrowseStudyLearningRecordsUseCase
import com.buddystudy.backend.study.application.port.outbound.StudyLearningRecordQueryPort
import com.buddystudy.backend.study.application.port.outbound.StudyPort
import com.buddystudy.backend.study.application.port.outbound.VoiceStudyLearningRecordQueryPort
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

/** Composition of private voice history and ordinary records, without creating pending/public questions. */
@Service
class StudyLearningRecordService(
    private val studies: StudyPort,
    private val keys: StudyLearningRecordQueryPort,
    private val voiceRecords: VoiceStudyLearningRecordQueryPort,
    private val voiceLocalizations: VoiceStudyLearningLocalizationPort,
    private val records: BrowseRecordsUseCase,
) : BrowseStudyLearningRecordsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequirePermission(Permissions.STUDY_READ, Permissions.RECORD_READ, Permissions.VOICE_TUTOR_READ)
    override suspend fun learningRecords(
        principal: Principal,
        studyId: Long,
        scope: String,
        limit: Int,
        cursor: String?,
        language: String,
        view: String,
    ): StudyLearningRecordsPageResponse {
        studies.findByIdAndUserId(studyId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Study not found.")
        val selectedScope = when (scope) {
            "node" -> StudyLearningRecordScope.NODE
            "subtree" -> StudyLearningRecordScope.SUBTREE
            else -> invalid("Invalid learning-record scope.")
        }
        val original = originalView(view)
        val target = QuestionLanguage.normalize(language)
        val pageLimit = limit.coerceIn(1, 100)
        val position = StudyLearningRecordsCursorPolicy.decode(cursor, principal.userId, studyId, selectedScope)
        val pageKeys = keys.pageKeys(principal.userId, studyId, selectedScope, pageLimit + 1, position)
        val consumed = pageKeys.take(pageLimit)
        val questions = consumed.filter { it.source == StudyLearningRecordSource.QUESTION }.map { it.recordId }
            .takeIf { it.isNotEmpty() }
            ?.let { records.recordsByIds(principal, it, target, view) }
            .orEmpty().associateBy { it.id }
        val voices = consumed.filter { it.source == StudyLearningRecordSource.VOICE_TUTOR }.map { it.recordId }
            .takeIf { it.isNotEmpty() }
            ?.let { voiceRecords.findAllOwned(principal.userId, it) }
            .orEmpty().filter { it.userId == principal.userId }.associateBy { it.id }
        val items = consumed.mapNotNull { key ->
            when (key.source) {
                StudyLearningRecordSource.QUESTION -> {
                    val record = questions[key.recordId.toString()]?.takeIf { it.studyId == key.studyId }
                        ?: return@mapNotNull null // Deleted/reparented between the key and content queries.
                    StudyLearningRecordResponse("question:${key.recordId}", key.source, key.studyId, key.createdAt, questionRecord = record)
                }
                StudyLearningRecordSource.VOICE_TUTOR -> {
                    val record = voices[key.recordId]?.takeIf { it.studyId == key.studyId } ?: return@mapNotNull null
                    StudyLearningRecordResponse(
                        "voice:${key.recordId}", key.source, key.studyId, key.createdAt,
                        voiceRecord = project(record, target, original),
                    )
                }
            }
        }
        val hasMore = pageKeys.size > pageLimit
        // Advance by consumed keys even if a record was deleted during hydration; no retry loop or offset drift.
        val next = consumed.lastOrNull()?.takeIf { hasMore }
            ?.let { StudyLearningRecordsCursorPolicy.encode(principal.userId, studyId, selectedScope, it) }
        return StudyLearningRecordsPageResponse(items, next, hasMore, pageLimit)
    }

    @RequirePermission(Permissions.VOICE_TUTOR_READ)
    override suspend fun voiceLearningRecord(
        principal: Principal,
        recordId: Long,
        language: String,
        view: String,
    ): VoiceStudyLearningRecordResponse {
        val original = originalView(view)
        val record = voiceRecords.findOwned(principal.userId, recordId)?.takeIf { it.userId == principal.userId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        return project(record, QuestionLanguage.normalize(language), original)
    }

    private suspend fun project(record: VoiceStudyLearningRecord, target: String, original: Boolean): VoiceStudyLearningRecordResponse {
        val sourceFields = record.translatableFields()
        val needsTranslation = !original && sourceFields.keys.any { (record.sourceLanguages[it] ?: record.sourceLanguage) != target }
        var fields = sourceFields
        var translated = false
        var pending = false
        if (needsTranslation) {
            try {
                val snapshot = voiceLocalizations.snapshot(record.id, target)
                val ready = snapshot?.takeIf {
                    it.status == "READY" && it.targetLanguage == target && it.sourceHash == record.sourceHash &&
                        sourceFields.keys.all { key -> !it.fields[key].isNullOrBlank() }
                }
                if (ready != null) {
                    fields = ready.fields
                    translated = true
                } else {
                    // Durable read repair goes through the same outbox/translation stream. Never block source display.
                    voiceLocalizations.request(record, target, Instant.now())
                    pending = snapshot?.takeIf { it.sourceHash == record.sourceHash }?.status != "FAILED"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Voice learning-record localization unavailable id={} failure={}", record.id, error.javaClass.simpleName)
            }
        }
        return VoiceStudyLearningRecordResponse(
            id = record.id.toString(), sessionId = record.sessionId,
            studyId = record.studyId, parentStudyId = record.parentStudyId, topic = record.topic,
            difficulty = record.difficulty, createdAt = record.createdAt, kind = record.kind,
            question = fields["question"] ?: record.question,
            answer = fields["answer"] ?: record.answer, score = record.score,
            strengths = record.strengths.mapIndexed { index, text -> fields["strength$index"] ?: text },
            improvements = record.improvements.mapIndexed { index, text -> fields["improvement$index"] ?: text },
            depthSummary = fields["depthSummary"] ?: record.depthSummary,
            feedback = fields["feedback"] ?: record.feedback,
            questionTurnId = record.questionTurnId, answerTurnIds = record.answerTurnIds,
            feedbackTurnIds = record.feedbackTurnIds,
            sourceLanguage = record.sourceLanguage, requestedLanguage = target,
            displayLanguage = if (translated) target else record.sourceLanguage, translationPending = pending,
        )
    }

    private fun originalView(view: String): Boolean = when (view) {
        "original" -> true
        "localized" -> false
        else -> invalid("Invalid learning-record view.")
    }

    private fun invalid(message: String): Nothing =
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, message)
}
