package com.buddystudy.backend.localization.application.policy

import com.buddystudy.backend.localization.application.model.RecordSourceHashes
import com.buddystudy.study.domain.entity.QuestionEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ContentSourceHashPolicy {
    fun recordHashes(question: QuestionEntity): RecordSourceHashes {
        val questionHash = sha256(
            listOf(
                question.topic,
                question.question,
                question.hint.orEmpty(),
            ).joinToString("\u001f"),
        )
        val answerHash = question.answer
            ?.takeIf(String::isNotBlank)
            ?.let(::sha256)
        val aiResponseHash = if (
            !question.feedback.isNullOrBlank() ||
            !question.explanation.isNullOrBlank() ||
            !question.gradingAssessmentJson.isNullOrBlank()
        ) {
            sha256(
                listOf(
                    question.feedback.orEmpty(),
                    question.explanation.orEmpty(),
                    question.gradingAssessmentJson.orEmpty(),
                ).joinToString("\u001f"),
            )
        } else {
            null
        }
        return RecordSourceHashes(
            record = sha256(
                listOf(questionHash, answerHash.orEmpty(), aiResponseHash.orEmpty())
                    .joinToString("\u001f"),
            ),
            question = questionHash,
            answer = answerHash,
            aiResponse = aiResponseHash,
        )
    }

    fun recordHash(question: QuestionEntity): String = recordHashes(question).record

    fun legacyRecordHash(question: QuestionEntity): String = sha256(
        listOf(
            question.topic,
            question.question,
            question.hint.orEmpty(),
            question.answer.orEmpty(),
            question.feedback.orEmpty(),
            question.explanation.orEmpty(),
            question.gradingAssessmentJson.orEmpty(),
        ).joinToString("\u001f"),
    )

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
