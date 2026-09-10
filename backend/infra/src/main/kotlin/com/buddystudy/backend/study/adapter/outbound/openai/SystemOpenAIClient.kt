package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.application.openai.SystemOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.outbound.StudyTopicSuggestionPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import com.buddystudy.backend.study.application.port.outbound.StudyCurriculumTopic
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
    override suspend fun suggestCurriculumTopics(rootTopic: String, parentTopic: String,
        existingTopics: Collection<String>, language: String, count: Int, rootDifficulty: Int): List<StudyCurriculumTopic> =
        runInterruptible(Dispatchers.IO) {
            executor.suggestStudyCurriculumTopics(keys.requireApiKey(), properties.openai.systemModel,
                rootTopic, parentTopic, existingTopics, language, count, rootDifficulty)
        }

}
