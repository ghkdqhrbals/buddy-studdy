package com.buddystudy.backend.community.application.port.inbound

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.community.application.model.CommunityCommentResponse
import com.buddystudy.backend.community.application.model.CommunityCommentDeleteResponse
import com.buddystudy.backend.community.application.model.CommunityCommentsResponse
import com.buddystudy.backend.community.application.model.CommunityLikeResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionsResponse
import com.buddystudy.backend.community.application.model.FeedbackResponse
import com.buddystudy.backend.community.application.model.UserBlockResponse

interface CommunityUseCase {
    suspend fun getPublicQuestions(
        principal: Principal?,
        query: String?,
        language: String,
        view: String = "localized",
        limit: Int,
        offset: Int,
    ): CommunityQuestionsResponse
    suspend fun getPublicQuestionsV2(
        principal: Principal?,
        query: String?,
        language: String,
        view: String = "localized",
        limit: Int,
        offset: Int,
    ): CommunityQuestionsResponse
    suspend fun getPublicQuestion(
        principal: Principal?,
        id: Long,
        language: String = "ko",
        view: String = "localized",
    ): CommunityQuestionResponse
    suspend fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse
    suspend fun getComments(
        id: Long,
        language: String = "ko",
        view: String = "localized",
        limit: Int,
        offset: Int,
        principal: Principal? = null,
    ): CommunityCommentsResponse
    suspend fun createComment(
        principal: Principal,
        id: Long,
        body: String,
        sourceLanguage: String? = null,
    ): CommunityCommentResponse
    suspend fun deleteComment(principal: Principal, id: Long, commentId: Long): CommunityCommentDeleteResponse
    suspend fun reportQuestion(principal: Principal, id: Long, command: ReportQuestionCommand)
    suspend fun setUserBlocked(principal: Principal, userId: Long, blocked: Boolean): UserBlockResponse
    suspend fun submitFeedback(principal: Principal?, deviceId: String?, command: SubmitFeedbackCommand): FeedbackResponse
    suspend fun recordNativeAdvertisementView(
        principal: Principal,
        selectionId: String,
    )
    suspend fun recordNativeAdvertisementImpression(
        principal: Principal,
        selectionId: String,
    )
    suspend fun suppressNativeAdvertisement(
        principal: Principal,
        selectionId: String,
    )
}
