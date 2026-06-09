package com.buddystuddy.backend.community.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.community.application.model.CommunityCommentResponse
import com.buddystuddy.backend.community.application.model.CommunityCommentDeleteResponse
import com.buddystuddy.backend.community.application.model.CommunityCommentsResponse
import com.buddystuddy.backend.community.application.model.CommunityLikeResponse
import com.buddystuddy.backend.community.application.model.CommunityQuestionResponse
import com.buddystuddy.backend.community.application.model.CommunityQuestionsResponse

interface CommunityUseCase {
    fun publicQuestions(principal: Principal?, query: String?, limit: Int, offset: Int): CommunityQuestionsResponse
    fun publicQuestion(principal: Principal?, id: Long): CommunityQuestionResponse
    fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse
    fun comments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse
    fun comment(principal: Principal, id: Long, body: String): CommunityCommentResponse
    fun deleteComment(principal: Principal, id: Long, commentId: Long): CommunityCommentDeleteResponse
    fun report(principal: Principal, id: Long, command: ReportQuestionCommand)
}
