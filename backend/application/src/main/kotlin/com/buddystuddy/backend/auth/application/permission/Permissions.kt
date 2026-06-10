package com.buddystuddy.backend.auth.application.permission

object Roles {
    const val ANONYMOUS_USER = "ANONYMOUS_USER"
    const val REGISTERED_USER = "REGISTERED_USER"
}

object Permissions {
    const val PROFILE_READ = "profile:read"
    const val PROFILE_UPDATE = "profile:update"
    const val PROFILE_WITHDRAW = "profile:withdraw"
    const val STUDY_READ = "study:read"
    const val STUDY_CREATE = "study:create"
    const val STUDY_UPDATE = "study:update"
    const val STUDY_DELETE = "study:delete"
    const val RECORD_READ = "record:read"
    const val RECORD_UPDATE = "record:update"
    const val RECORD_DELETE = "record:delete"
    const val RECORD_PUBLISH = "record:publish"
    const val STATS_READ = "stats:read"
    const val PUBLIC_QUESTION_READ = "public-question:read"
    const val PUBLIC_QUESTION_LIKE = "public-question:like"
    const val PUBLIC_QUESTION_COMMENT = "public-question:comment"
    const val PUBLIC_QUESTION_REPORT = "public-question:report"
    const val COMMENT_DELETE = "comment:delete"
    const val DEBUG_READ = "debug:read"
    const val TEST_PUSH_SEND = "test-push:send"
    const val ADMIN_READ = "admin:read"
    const val ADMIN_WRITE = "admin:write"
}
