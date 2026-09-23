package com.buddystudy.backend.study.adapter.outbound.translation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private val libreTranslateJson = jacksonObjectMapper()

/** Read the provider's exact wire field, without Kotlin DTO default-value coercion. */
internal fun decodeLibreTranslateResponse(body: String?): String {
    require(!body.isNullOrBlank()) { "LibreTranslate returned an empty response body." }
    val value = try {
        libreTranslateJson.readTree(body)?.get("translatedText")
    } catch (_: Exception) {
        throw IllegalArgumentException("LibreTranslate returned malformed JSON.")
    }
    require(value != null && value.isTextual) { "LibreTranslate returned no textual translatedText field." }
    return value.textValue().trim().also {
        require(it.isNotEmpty()) { "LibreTranslate returned empty content." }
    }
}
