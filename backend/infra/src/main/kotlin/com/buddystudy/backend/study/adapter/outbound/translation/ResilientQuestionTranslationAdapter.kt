package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
import com.buddystudy.study.domain.QuestionLanguage
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ResilientQuestionTranslationAdapter(
    providers: List<QuestionTranslationProvider>,
    private val properties: BuddyStudyProperties,
    private val meterRegistry: MeterRegistry,
) : QuestionTranslationPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val providersById = providers.associateBy { it.providerId.lowercase() }

    override suspend fun translate(
        topic: String,
        question: String,
        hint: String?,
        sourceLanguage: String,
        targetLanguage: String,
        validationMode: TranslationValidationMode,
    ): TranslatedQuestionContent {
        val request = QuestionTranslationRequest(topic, question, hint, sourceLanguage, targetLanguage)
        val providerOrder = properties.translation.providerOrder
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .distinct()
        val configuredProviders = providerOrder.mapNotNull { providerId ->
            providersById[providerId].also {
                if (it == null) log.warn("translation_provider_not_registered provider={}", providerId)
            }
        }
        check(configuredProviders.isNotEmpty()) {
            "No configured translation provider is available."
        }

        var lastFailure: Exception? = null
        configuredProviders.forEach { provider ->
            try {
                val translated = provider.translate(request)
                validate(translated, targetLanguage, validationMode)
                count(provider.providerId, "success")
                return translated
            } catch (error: Exception) {
                lastFailure = error
                count(provider.providerId, "failure")
                log.warn(
                    "translation_provider_failed provider={} sourceLanguage={} errorType={} error={}",
                    provider.providerId,
                    sourceLanguage,
                    error.javaClass.simpleName,
                    error.message,
                )
            }
        }

        throw IllegalStateException(
            "All configured translation providers failed: ${configuredProviders.joinToString { it.providerId }}",
            lastFailure,
        )
    }

    private fun validate(
        content: TranslatedQuestionContent,
        targetLanguage: String,
        validationMode: TranslationValidationMode,
    ) {
        require(QuestionLanguage.matchesShortLabel(content.topic, targetLanguage)) {
            "Translation provider did not return a topic in $targetLanguage."
        }
        val questionMatches = when (validationMode) {
            TranslationValidationMode.QUESTION -> QuestionLanguage.matches(content.question, targetLanguage)
            TranslationValidationMode.SHORT_TEXT -> QuestionLanguage.matchesShortLabel(content.question, targetLanguage)
        }
        require(questionMatches) {
            "Translation provider did not return a question in $targetLanguage."
        }
        content.hint?.takeIf(String::isNotBlank)?.let { hint ->
            require(QuestionLanguage.matchesShortLabel(hint, targetLanguage)) {
                "Translation provider did not return a hint in $targetLanguage."
            }
        }
    }

    private fun count(provider: String, outcome: String) {
        meterRegistry.counter(
            "buddystudy.translation.requests",
            "provider", provider,
            "outcome", outcome,
        ).increment()
    }
}
