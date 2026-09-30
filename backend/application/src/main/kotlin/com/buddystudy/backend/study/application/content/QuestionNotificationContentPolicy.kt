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

    fun gradingCompletedTitle(language: String): String = when {
        language.lowercase().startsWith("en") -> "Grading complete"
        language.lowercase().startsWith("ja") -> "採点が完了しました"
        else -> "채점이 완료됐어요"
    }

    fun gradingCompletedBody(language: String): String = when {
        language.lowercase().startsWith("en") -> "Your answer has been graded. Tap to see your result and feedback."
        language.lowercase().startsWith("ja") -> "回答の採点が完了しました。タップして結果とフィードバックを確認してください。"
        else -> "답변의 채점이 끝났어요. 알림을 눌러 결과와 피드백을 확인해 보세요."
    }
}
