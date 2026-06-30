package com.buddystudy.backend.community.application.model

import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.backend.profile.application.model.UserProfileResponse

fun QuestionCommentEntity.toResponse(author: UserProfileResponse) =
    CommunityCommentResponse(id.toString(), questionId.toString(), body, createdAt, author)
