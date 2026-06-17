package com.buddystuddy.backend.community.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.community.application.model.CommunityCommentResponse
import com.buddystuddy.backend.community.application.model.CommunityCommentDeleteResponse
import com.buddystuddy.backend.community.application.model.CommunityCommentsResponse
import com.buddystuddy.backend.community.application.model.CommunityLikeResponse
import com.buddystuddy.backend.community.application.model.CommunityQuestionResponse
import com.buddystuddy.backend.community.application.model.CommunityQuestionsResponse

interface CommunityUseCase {
    fun getPublicQuestions(principal: Principal?, query: String?, language: String, limit: Int, offset: Int): CommunityQuestionsResponse
    fun getPublicQuestionsV2(principal: Principal?, query: String?, language: String, limit: Int, offset: Int): CommunityQuestionsResponse
    fun getPublicQuestion(principal: Principal?, id: Long, language: String): CommunityQuestionResponse
    fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse
    fun getComments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse
    fun createComment(principal: Principal, id: Long, body: String): CommunityCommentResponse
    fun deleteComment(principal: Principal, id: Long, commentId: Long): CommunityCommentDeleteResponse
    fun reportQuestion(principal: Principal, id: Long, command: ReportQuestionCommand)
}
