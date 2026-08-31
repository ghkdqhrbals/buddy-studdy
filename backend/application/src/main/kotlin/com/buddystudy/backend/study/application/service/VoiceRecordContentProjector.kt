package com.buddystudy.backend.study.application.service

import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.study.application.model.ContentLocalizationResponse
import com.buddystudy.backend.study.application.model.RecordLocalizationResponse
import com.buddystudy.backend.study.application.model.TranslationViewMode
import com.buddystudy.backend.study.application.model.VoiceRecordContentResponse
import com.buddystudy.backend.study.application.model.VoiceRecordProjection
import com.buddystudy.backend.study.application.model.originalLocalization
import com.buddystudy.backend.study.application.model.translatedLocalization
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.StudyRecordType
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Instant

/** Projects only an already-authorized canonical record, without exposing its private session evidence.
 * Both public and owner reads use the original voice translation stream and its guarded read repair.
 */
@Component
class VoiceRecordContentProjector(
    private val localizations: VoiceStudyLearningLocalizationPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun project(
        question: QuestionEntity,
        target: String,
        viewMode: TranslationViewMode,
    ): VoiceRecordProjection? {
        if (question.recordType != StudyRecordType.VOICE_TUTOR) return null
        val extensionId = question.voiceRecordId ?: missing()
        val record = localizations.content(extensionId)?.takeIf {
            it.recordId == question.id && it.userId == question.userId
        } ?: missing()
        val requested = QuestionLanguage.normalize(target)
        val sourceFields = record.translatableFields()
        val original = viewMode == TranslationViewMode.ORIGINAL
        val needsTranslation = !original && sourceFields.keys.any {
            QuestionLanguage.normalize(record.sourceLanguages[it] ?: record.sourceLanguage) != requested
        }
        var fields = sourceFields
        var translated = false
        var pending = false
        if (needsTranslation) {
            try {
                val snapshot = localizations.snapshot(record.id, requested)
                val ready = snapshot?.takeIf {
                    it.status == "READY" && it.targetLanguage == requested && it.sourceHash == record.sourceHash &&
                        sourceFields.keys.all { key -> !it.fields[key].isNullOrBlank() }
                }
                if (ready != null) {
                    fields = ready.fields
                    translated = true
                } else {
                    localizations.request(record, requested, Instant.now())
                    pending = snapshot?.takeIf { it.sourceHash == record.sourceHash }?.status != "FAILED"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // No original text, provider payload, or throwable message in shared record logs.
                log.warn("Voice record localization unavailable recordId={} failure={}", question.id, error.javaClass.simpleName)
            }
        }
        fun metadata(field: String): ContentLocalizationResponse {
            val source = QuestionLanguage.normalize(record.sourceLanguages[field] ?: record.sourceLanguage)
            return if (translated && source != requested) translatedLocalization(source, requested)
            else originalLocalization(source, requested, viewMode, pending = pending && source != requested)
        }
        return VoiceRecordProjection(
            question = fields["question"] ?: record.question,
            answer = fields["answer"] ?: record.answer,
            content = VoiceRecordContentResponse(
                kind = record.kind,
                score = record.score,
                feedback = fields["feedback"] ?: record.feedback,
                strengths = record.strengths.mapIndexed { index, value -> fields["strength$index"] ?: value },
                improvements = record.improvements.mapIndexed { index, value -> fields["improvement$index"] ?: value },
                depthSummary = fields["depthSummary"] ?: record.depthSummary,
                sourceLanguage = record.sourceLanguage,
                requestedLanguage = requested,
                displayLanguage = if (translated) requested else record.sourceLanguage,
                translationPending = pending,
            ),
            localization = RecordLocalizationResponse(
                question = metadata("question"),
                answer = record.answer?.let { metadata("answer") },
                aiResponse = metadata(if (!record.feedback.isNullOrBlank()) "feedback" else "depthSummary"),
            ),
        )
    }

    private fun missing(): Nothing =
        throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
}
