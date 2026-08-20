package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.openai.SystemOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.outbound.StudyTopicSuggestionPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class SystemOpenAIClient(
    private val executor: OpenAIRequestExecutor,
    private val keys: SystemOpenAIKeyProvider,
    private val properties: BuddyStudyProperties,
) : StudyTopicSuggestionPort {
    override suspend fun suggestTopics(
        rootTopic: String,
        parentTopic: String,
        existingTopics: Collection<String>,
        language: String,
        count: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        executor.suggestStudyTopics(
            apiKey = keys.requireApiKey(),
            model = properties.openai.systemModel,
            rootTopic = rootTopic,
            parentTopic = parentTopic,
            existingTopics = existingTopics,
            language = language,
            count = count,
        )
    }
}
