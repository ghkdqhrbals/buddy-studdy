package com.buddystudy.backend.study.application.content

import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer

object MarkdownContentPolicy {
    const val GENERATION_GUIDE: String =
        "Every question, expectedAnswerHint, feedback, and explanation value must be valid Markdown. " +
            "Put every choice or list item on its own line. Use '- ' for bullets and '1. ' for ordered lists. " +
            "For lettered choices use '- **A.** choice', never inline 'A) choice B) choice'. " +
            "Use backticks for identifiers and fenced code blocks with a language when code spans multiple lines. " +
            "Do not emit HTML or wrap the entire field in a code fence."

    fun plainText(markdown: String): String =
        textRenderer.render(parser.parse(markdown)).trim()

    private val parser = Parser.builder().build()
    private val textRenderer = TextContentRenderer.builder().build()
}
