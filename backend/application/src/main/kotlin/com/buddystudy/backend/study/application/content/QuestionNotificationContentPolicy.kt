package com.buddystudy.backend.study.application.content

object QuestionNotificationContentPolicy {
    fun title(appLanguage: String): String =
        if (appLanguage.lowercase().startsWith("en")) "New Question" else "새 질문 도착"

    fun preview(markdown: String): String =
        MarkdownContentPolicy.plainText(markdown)
}
