package com.buddystudy.backend.voice.adapter.outbound.openai

/** Server-owned next actions make cancelling an answer independent of model compliance. */
internal object VoiceTutorLearningCancellation {
    fun choices(language: String): Map<String, Any> {
        val labels = when (language) {
            "en" -> listOf("Learning stopped", "Your question and draft are kept. What would you like to do next?",
                "Choose another topic", "Chat freely", "Continue learning later")
            "ja" -> listOf("学習を中止しました", "問題と下書きは残っています。次はどうしますか？",
                "別のテーマを選ぶ", "自由に話す", "後で学習を続ける")
            else -> listOf("학습을 멈췄어요", "문제와 작성한 내용은 유지돼요. 다음엔 무엇을 할까요?",
                "다른 주제 고르기", "자유롭게 대화하기", "나중에 이어하기")
        }
        return mapOf("title" to labels[0], "questions" to listOf(mapOf(
            "id" to "after_cancelled_answer", "prompt" to labels[1], "selectionMode" to "single",
            "allowFreeText" to true, "options" to listOf("another_topic", "free_conversation", "later")
                .mapIndexed { index, id -> mapOf("id" to id, "label" to labels[index + 2]) },
        )))
    }

    fun noticeInstructions(language: String): String = "Say exactly this brief cancellation acknowledgement once, then listen. " +
        "Do not ask a question, call any tool, restart learning or end the call: " + when (language) {
        "en" -> "Cancelled. We can just talk whenever you're ready."
        "ja" -> "キャンセルしました。話したいことがあれば、どうぞ。"
        else -> "취소했어요. 편하게 이야기해 주세요."
    }
}
