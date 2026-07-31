package com.buddystudy.backend.localization.application.service

import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.study.domain.QuestionLanguage
import com.buddystudy.study.domain.entity.QuestionEntity

fun QuestionEntity.applyReadyQuestionLocalization(
    snapshot: RecordLocalizationSnapshot,
    targetLanguage: String,
): QuestionEntity {
    val target = QuestionLanguage.normalize(targetLanguage)
    if (QuestionLanguage.normalize(sourceLanguage.databaseValue) == target) return this
    val expectedHash = ContentLocalizationService.recordHashes(this).question
    val localized = snapshot.question
        ?.takeIf { it.status == "READY" && it.sourceHash == expectedHash }
        ?: return this
    topic = localized.fields["topic"] ?: topic
    question = localized.fields["question"] ?: question
    hint = localized.fields["hint"] ?: hint
    return this
}
