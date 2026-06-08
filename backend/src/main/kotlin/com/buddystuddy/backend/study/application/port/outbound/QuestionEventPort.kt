package com.buddystuddy.backend.study.application.port.outbound

enum class QuestionStreamEventType {
    QUESTION_PUSH_REQUESTED,
    CONTENT_VIEWED,
    QUESTION_LIKED,
    QUESTION_UNLIKED,
    QUESTION_COMMENTED,
    QUESTION_COMMENT_DELETED,
}

interface QuestionEngagementEventPort {
    fun publishQuestionViewed(questionId: Long, userId: Long?): Boolean
    fun publishQuestionChanged(questionId: Long, eventType: QuestionStreamEventType, userId: Long?): Boolean
}
