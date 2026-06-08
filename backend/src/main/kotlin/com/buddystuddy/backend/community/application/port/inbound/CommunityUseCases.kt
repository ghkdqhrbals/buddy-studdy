package com.buddystuddy.backend.community.application.port.inbound

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.dto.CommunityCommentResponse
import com.buddystuddy.backend.dto.CommunityCommentsResponse
import com.buddystuddy.backend.dto.CommunityLikeResponse
import com.buddystuddy.backend.dto.CommunityQuestionResponse
import com.buddystuddy.backend.dto.CommunityQuestionsResponse
import com.buddystuddy.backend.dto.ReportQuestionRequest

interface CommunityUseCase {
    fun publicQuestions(principal: Principal?, topic: String?, limit: Int, offset: Int): CommunityQuestionsResponse
    fun publicQuestion(principal: Principal?, id: Long): CommunityQuestionResponse
    fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse
    fun comments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse
    fun comment(principal: Principal, id: Long, body: String): CommunityCommentResponse
    fun report(principal: Principal, id: Long, payload: ReportQuestionRequest)
}
