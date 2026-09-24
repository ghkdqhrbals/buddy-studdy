package com.buddystudy.backend.study.application.model

/** Bounded original question and at most two follow-up records, in chronological order. */
data class QuestionThreadResponse(val records: List<StudyRecordResponse>)
