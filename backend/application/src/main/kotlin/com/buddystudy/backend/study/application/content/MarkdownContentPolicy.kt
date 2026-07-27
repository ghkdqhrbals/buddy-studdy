package com.buddystudy.backend.study.application.content

object MarkdownContentPolicy {
    const val GENERATION_GUIDE: String =
        "Use Markdown inside text fields only when it improves clarity. " +
            "Allowed forms are paragraphs, emphasis, lists, inline code, and fenced code blocks. " +
            "Do not emit HTML or wrap the entire field in a code fence."

    fun plainText(markdown: String): String =
        markdown
            .replace(FENCED_CODE, "$1")
            .replace(IMAGE, "$1")
            .replace(LINK, "$1")
            .lineSequence()
            .joinToString(" ") { line ->
                line.replace(LEADING_BLOCK_MARKER, "")
            }
            .replace(INLINE_CODE, "$1")
            .replace(BOLD_ASTERISK, "$1")
            .replace(BOLD_UNDERSCORE, "$1")
            .replace(STRIKETHROUGH, "$1")
            .replace(WHITESPACE, " ")
            .trim()

    private val FENCED_CODE = Regex("(?s)```(?:[^\\n]*)\\n?(.*?)```")
    private val IMAGE = Regex("!\\[([^]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val LEADING_BLOCK_MARKER = Regex("^\\s{0,3}(?:#{1,6}\\s+|>\\s?|[-+]\\s+|\\d+[.)]\\s+)")
    private val INLINE_CODE = Regex("`([^`\\n]+)`")
    private val BOLD_ASTERISK = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val BOLD_UNDERSCORE = Regex("__([^_\\n]+)__")
    private val STRIKETHROUGH = Regex("~~([^~\\n]+)~~")
    private val WHITESPACE = Regex("\\s+")
}
