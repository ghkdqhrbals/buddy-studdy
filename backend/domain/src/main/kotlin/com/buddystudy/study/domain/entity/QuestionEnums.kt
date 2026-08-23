package com.buddystudy.study.domain.entity

enum class QuestionStatus(
    val databaseValue: String,
) {
    UNGRADED("ungraded"),
    GRADING("grading"),
    GRADED("graded"),
    FAILED("failed"),
    SKIPPED("skipped"),
    ;

    val allowsNextQuestion: Boolean
        get() = this in COMPLETED_STATUSES || this == SKIPPED

    companion object {
        val COMPLETED_STATUSES: Set<QuestionStatus> = setOf(FAILED, GRADED)

        fun fromDatabaseValue(value: String): QuestionStatus =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported question status database value: $value")
    }
}

enum class QuestionSource(
    val databaseValue: String,
) {
    SCHEDULED("scheduled"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): QuestionSource =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported question source database value: $value")
    }
}

enum class GradingVerdict {
    CORRECT,
    PARTIALLY_CORRECT,
    INCORRECT,
}

enum class AnswerGradingStatus {
    QUEUED,
    ANALYZING_EVIDENCE,
    CRITIQUING,
    JUDGING,
    ADJUDICATING,
    COMPLETED,
    FAILED,
    ;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED
}
