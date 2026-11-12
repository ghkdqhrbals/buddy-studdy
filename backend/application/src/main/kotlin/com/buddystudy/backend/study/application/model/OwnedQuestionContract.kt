package com.buddystudy.backend.study.application.model

import com.buddystudy.study.domain.entity.QuestionFormat

/** Legacy owners keep their pending question, represented by their existing text-answer contract. */
fun StudyRecordResponse.forQuestionContract(version: Int): StudyRecordResponse {
    if (version >= 3 || questionFormat != QuestionFormat.MULTIPLE_CHOICE) return this
    val choices = multipleChoice?.options.orEmpty()
    val instruction = when (localization?.question?.displayLanguage) {
        "ko" -> "정답 번호(1~${choices.size})만 입력하세요."
        "ja" -> "正解の番号（1〜${choices.size}）だけを入力してください。"
        else -> "Enter only the answer number (1–${choices.size})."
    }
    val numbered = choices.mapIndexed { index, option -> "${index + 1}. ${option.text}" }.joinToString("\n")
    return copy(
        question = question.copy(question = listOf(question.question, numbered, instruction).joinToString("\n\n")),
        questionFormat = QuestionFormat.FREE_TEXT,
        multipleChoice = null,
    )
}
