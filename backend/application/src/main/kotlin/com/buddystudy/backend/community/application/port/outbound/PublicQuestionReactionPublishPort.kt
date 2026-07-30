package com.buddystudy.backend.community.application.port.outbound

interface PublicQuestionReactionPublishPort {
    suspend fun publishViewed(
        questionId: Long,
        userId: Long?,
        localization: PublicQuestionViewLocalization? = null,
    ): Boolean

    suspend fun publishLiked(questionId: Long, userId: Long): Boolean
    suspend fun publishUnliked(questionId: Long, userId: Long): Boolean
    suspend fun publishCommented(questionId: Long, commentId: Long, userId: Long): Boolean
    suspend fun publishCommentDeleted(questionId: Long, commentId: Long, userId: Long): Boolean
}

data class PublicQuestionViewLocalization(
    val translationState: String,
    val translationLanguage: String,
    val translationReason: String,
    val requestId: String,
    val questionSourceLanguage: String,
    val questionDisplayLanguage: String,
    val answerSourceLanguage: String? = null,
    val answerDisplayLanguage: String? = null,
    val aiResponseSourceLanguage: String? = null,
    val aiResponseDisplayLanguage: String? = null,
)
