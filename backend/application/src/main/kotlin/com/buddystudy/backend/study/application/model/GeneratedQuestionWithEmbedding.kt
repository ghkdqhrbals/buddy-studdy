package com.buddystudy.backend.study.application.model

import com.buddystudy.backend.study.application.port.outbound.GeneratedQuestion

data class GeneratedQuestionWithEmbedding(
    val generated: GeneratedQuestion,
    val embedding: List<Float>,
) {
    val question: String get() = generated.question
    val hint: String? get() = generated.hint
}
