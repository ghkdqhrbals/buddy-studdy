package com.buddystudy.backend.study.application.content

object QuestionNotificationContentPolicy {
    fun title(appLanguage: String): String =
        when {
            appLanguage.lowercase().startsWith("en") -> "New Question"
            appLanguage.lowercase().startsWith("ja") -> "新しい質問"
            else -> "새 질문 도착"
        }

    fun preview(markdown: String): String =
        MarkdownContentPolicy.plainText(markdown)
}
