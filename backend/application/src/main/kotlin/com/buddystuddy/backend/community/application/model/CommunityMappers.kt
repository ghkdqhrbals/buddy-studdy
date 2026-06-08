package com.buddystuddy.backend.community.application.model

import com.buddystuddy.domain.QuestionCommentEntity
import com.buddystuddy.backend.profile.application.model.UserProfileResponse

fun QuestionCommentEntity.toResponse(author: UserProfileResponse) =
    CommunityCommentResponse(id.toString(), questionId.toString(), body, createdAt, author)
