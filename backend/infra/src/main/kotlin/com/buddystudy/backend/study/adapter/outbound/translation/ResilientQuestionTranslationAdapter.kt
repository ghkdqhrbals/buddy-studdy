package com.buddystudy.backend.study.adapter.outbound.translation

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.port.outbound.QuestionTranslationPort
import com.buddystudy.backend.study.application.port.outbound.TranslationValidationMode
import com.buddystudy.study.domain.QuestionLanguage
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
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
        val preserved = TranslatedQuestionContent(topic, question, hint)
        if (isValidTranslation(request, preserved, validationMode)) {
            count("identity", "success")
            return preserved
        }
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
                validate(request, translated, validationMode)
                count(provider.providerId, "success")
                return translated
            } catch (error: CancellationException) {
                throw error
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
        request: QuestionTranslationRequest,
        content: TranslatedQuestionContent,
        validationMode: TranslationValidationMode,
    ) {
        require(topicMatches(request, content)) {
            "Translation provider did not return a topic in ${request.targetLanguage}."
        }
        require(questionMatches(request, content, validationMode)) {
            "Translation provider did not return a question in ${request.targetLanguage}."
        }
    }

    private fun isValidTranslation(
        request: QuestionTranslationRequest,
        content: TranslatedQuestionContent,
        validationMode: TranslationValidationMode,
    ): Boolean = topicMatches(request, content) && questionMatches(request, content, validationMode)

    private fun topicMatches(
        request: QuestionTranslationRequest,
        content: TranslatedQuestionContent,
    ): Boolean = QuestionLanguage.matchesTranslation(
        source = request.topic,
        translated = content.topic,
        targetLanguage = request.targetLanguage,
        shortLabel = true,
    )

    private fun questionMatches(
        request: QuestionTranslationRequest,
        content: TranslatedQuestionContent,
        validationMode: TranslationValidationMode,
    ): Boolean = when (validationMode) {
        TranslationValidationMode.QUESTION -> QuestionLanguage.matchesTranslation(
            source = request.question,
            translated = content.question,
            targetLanguage = request.targetLanguage,
        )

        TranslationValidationMode.SHORT_TEXT -> QuestionLanguage.matchesTranslation(
            source = request.question,
            translated = content.question,
            targetLanguage = request.targetLanguage,
            shortLabel = true,
        )
    }

    private fun count(provider: String, outcome: String) {
        meterRegistry.counter(
            "buddystudy.translation.requests",
            "provider", provider,
            "outcome", outcome,
        ).increment()
    }
}
