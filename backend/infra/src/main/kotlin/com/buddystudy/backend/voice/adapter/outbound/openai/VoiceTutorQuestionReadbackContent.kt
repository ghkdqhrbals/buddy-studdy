package com.buddystudy.backend.voice.adapter.outbound.openai

import java.text.Normalizer
import java.util.Locale

/**
 * Checks provider output against the saved source, never against inferred learner intent.
 * Different or incomplete speech fails closed so the controller can use its bounded retry;
 * a status notice, paraphrase or hint must not authorize an answer to an unheard question.
 */
internal object VoiceTutorQuestionReadbackContent {
    fun matches(question: String, transcript: String): Boolean {
        if (question.isBlank() || transcript.isBlank() || question.length > 8_000 || transcript.length > 20_000) return false
        val expected = normalize(question)
        val actual = normalize(transcript)
        if (expected.isEmpty() || actual.isEmpty()) return false
        if (expected == actual) return true
        // Expand only labels/numbers that actually occur in the saved source.
        // Do not replace arbitrary learner/provider words globally or accept a
        // similar-looking question. All other source text still has to match.
        val source = decorations(question)
        val tokens = spokenToken.findAll(source).filter { token ->
            val before = source.getOrNull(token.range.first - 1)
            val after = source.getOrNull(token.range.last + 1)
            val preceding = source.substring(0, token.range.first).substringAfterLast('\n').lastOrNull { !it.isWhitespace() }
            val following = source.substring(token.range.last + 1).substringBefore('\n').firstOrNull { !it.isWhitespace() }
            preceding !in expressionMarks && following !in expressionMarks &&
                !(after == '.' && source.getOrNull(token.range.last + 2)?.isDigit() == true) &&
                !(before == '.' && source.getOrNull(token.range.first - 2)?.isDigit() == true)
        }.take(129).toList()
        if (tokens.isEmpty() || tokens.size > 128) return false
        var positions = setOf(0)
        var offset = 0
        for (token in tokens) {
            val prefix = normalize(source.substring(offset, token.range.first))
            val alternatives = pronunciations(token.value).map(::normalize).toSet()
            positions = positions.flatMap { start ->
                if (!actual.startsWith(prefix, start)) emptyList()
                else alternatives.mapNotNull { alternative ->
                    val at = start + prefix.length
                    (at + alternative.length).takeIf { actual.startsWith(alternative, at) }
                }
            }.toSet()
            if (positions.isEmpty()) return false
            offset = token.range.last + 1
        }
        val suffix = normalize(source.substring(offset))
        return positions.any { actual.substring(it) == suffix }
    }

    private fun pronunciations(token: String): List<String> {
        if (token.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            val values = mutableListOf(token)
            technicalNames[token.lowercase(Locale.ROOT)]?.let { values += it }
            if (token.length == 1 || (token.length <= 12 && token.all { it.isUpperCase() })) {
                values += token.map { koreanLetters[it.lowercaseChar() - 'a'] }.joinToString("")
                values += token.map { japaneseLetters[it.lowercaseChar() - 'a'] }.joinToString("")
            }
            return values
        }
        val number = token.toIntOrNull() ?: return listOf(token)
        if (number !in 0..99 || token != number.toString()) return listOf(token)
        val values = mutableListOf(token)
        values += if (number < 10) koreanDigits[number] else
            (if (number / 10 == 1) "" else koreanDigits[number / 10]) + "십" +
                (if (number % 10 == 0) "" else koreanDigits[number % 10])
        if (number in 1..10) {
            values += koreanNative[number - 1]
            values += japaneseNumbers[number]
            values += japaneseNumberReadings[number]
        }
        if (number in 1..4) values += listOf("한", "두", "세", "네")[number - 1]
        values += if (number < 20) englishSmall[number] else englishTens[number / 10] +
            (if (number % 10 == 0) "" else " " + englishSmall[number % 10])
        if (number >= 20 && number % 10 != 0) values += values.last().replace(' ', '-')
        return values
    }

    private fun decorations(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        // Source-only Markdown markers must not become spoken words/operators.
        val emphasized = emphasis.replace(normalized) { it.groupValues[2] }
        return markdownLineMarker.replace(emphasized, "")
    }

    private fun normalize(value: String): String {
        val content = decorations(value).lowercase(Locale.ROOT)
        val points = content.codePoints().toArray()
        return buildString(content.length) {
            points.forEachIndexed { index, point ->
                if (space(point) || point == '`'.code) return@forEachIndexed
                if (Character.getType(point) !in punctuation || meaningfulPunctuation(points, index)) appendCodePoint(point)
            }
        }
    }

    private fun meaningfulPunctuation(points: IntArray, index: Int): Boolean {
        val point = points[index]
        // Unicode mathematical symbols (+, =, <, >, etc.) are already retained.
        // Retain punctuation used as operators too: removing it could equate
        // != with =, a/b with ab, 1-2 with 12, or 1.5 with 15.
        if (point in intArrayOf('/'.code, '\\'.code, '%'.code, '&'.code)) return true
        val before = neighbour(points, index, -1)
        val after = neighbour(points, index, 1)
        return when (point) {
            '!'.code -> after == '='.code || points.getOrNull(index + 1)?.let(Character::isLetterOrDigit) == true
            '-'.code -> !lineBullet(points, index) && (after?.let(Character::isDigit) == true || (operand(before) && operand(after)))
            '*'.code -> operand(neighbour(points, index, -1, '*'.code)) && operand(neighbour(points, index, 1, '*'.code))
            '_'.code -> operand(before) && operand(after)
            '.'.code, ':'.code -> before?.let(Character::isDigit) == true && after?.let(Character::isDigit) == true
            '?'.code -> before == '?'.code || after == '?'.code || after == ':'.code
            else -> false
        }
    }

    private fun lineBullet(points: IntArray, index: Int): Boolean {
        if (points.getOrNull(index + 1)?.let(::space) != true) return false
        var cursor = index - 1
        while (cursor >= 0 && points[cursor] != '\n'.code && points[cursor] != '\r'.code) {
            if (!space(points[cursor])) return false
            cursor--
        }
        return true
    }

    private fun neighbour(points: IntArray, index: Int, direction: Int, ignored: Int? = null): Int? {
        var cursor = index + direction
        while (cursor in points.indices) {
            if (!space(points[cursor]) && points[cursor] != ignored) return points[cursor]
            cursor += direction
        }
        return null
    }

    private fun operand(point: Int?): Boolean = point != null &&
        (Character.isLetterOrDigit(point) || point in intArrayOf('('.code, ')'.code, '['.code, ']'.code))

    private fun space(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point)

    private val emphasis = Regex("(?<![\\p{L}\\p{N}])(\\*{1,3}|_{1,3}|~~)(?=\\S)([^\\r\\n]*?\\S)\\1(?![a-z0-9])")
    private val markdownLineMarker = Regex("(?m)^[ \t]{0,3}(?:[-*+] +|> +|```[A-Za-z0-9_-]*[ \t]*(?:$|\n))")
    private val spokenToken = Regex("(?<![A-Za-z0-9])[A-Za-z]+(?![A-Za-z0-9])|(?<![A-Za-z0-9])[0-9]+")
    private val expressionMarks = setOf('+', '-', '*', '/', ':', '<', '>', '=', '!', '?', '_', '%', '&', '\\')
    private val technicalNames = mapOf(
        "redis" to listOf("레디스"), "spring" to listOf("스프링"), "java" to listOf("자바"),
        "kotlin" to listOf("코틀린"), "mysql" to listOf("마이에스큐엘"),
        "postgresql" to listOf("포스트그레스큐엘", "포스트그레에스큐엘"),
        "docker" to listOf("도커"), "kubernetes" to listOf("쿠버네티스"),
    )
    private val koreanLetters = listOf("에이", "비", "씨", "디", "이", "에프", "지", "에이치", "아이", "제이", "케이", "엘", "엠", "엔", "오", "피", "큐", "알", "에스", "티", "유", "브이", "더블유", "엑스", "와이", "제트")
    private val japaneseLetters = listOf("エー", "ビー", "シー", "ディー", "イー", "エフ", "ジー", "エイチ", "アイ", "ジェー", "ケー", "エル", "エム", "エヌ", "オー", "ピー", "キュー", "アール", "エス", "ティー", "ユー", "ブイ", "ダブリュー", "エックス", "ワイ", "ゼット")
    private val koreanDigits = listOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val koreanNative = listOf("하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉", "열")
    private val japaneseNumbers = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    private val japaneseNumberReadings = listOf("れい", "いち", "に", "さん", "よん", "ご", "ろく", "なな", "はち", "きゅう", "じゅう")
    private val englishSmall = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val englishTens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val punctuation = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
    )
}
