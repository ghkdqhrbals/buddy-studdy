package com.buddystudy.notification.domain.entity

enum class NotificationType {
    ACTIVITY,
    THREAD_ACTIVITY,
    STUDY_QUESTION,
    QUESTION_READY,
    ADMIN_MESSAGE,
    MARKETING,
}

enum class NotificationThreadType(
    val databaseValue: String,
) {
    QUESTION("question"),
    STUDY_QUESTION("study_question"),
    ADMIN_MESSAGE("admin_message"),
    COMMENT("comment"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): NotificationThreadType =
            entries.firstOrNull { it.databaseValue == value }
                ?: error("Unsupported notification thread type database value: $value")
    }
}
