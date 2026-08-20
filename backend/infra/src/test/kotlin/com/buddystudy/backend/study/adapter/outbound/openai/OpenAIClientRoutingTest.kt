package com.buddystudy.backend.study.adapter.outbound.openai

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.study.adapter.outbound.translation.QuestionTranslationRequest
import com.buddystudy.backend.study.application.model.TranslatedQuestionContent
import com.buddystudy.backend.study.application.openai.SystemOpenAIKeyProvider
import com.buddystudy.backend.study.application.openai.UserContentOpenAIKeyProvider
import com.buddystudy.backend.study.application.port.outbound.AiGradingStage
import com.buddystudy.backend.study.application.port.outbound.GradedAnswer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.concurrent.atomic.AtomicReference

class OpenAIClientRoutingTest {
    @Test
    fun `post study suggestions use only the system OpenAI key and model`() = runBlocking {
        val properties = workloadProperties()
        val executor = Mockito.mock(OpenAIRequestExecutor::class.java)
        val callerThread = Thread.currentThread().name
        val executorThread = AtomicReference<String>()
        Mockito.`when`(
            executor.suggestStudyTopics(
                apiKey = "system-key",
                model = "system-model",
                rootTopic = "Databases",
                parentTopic = "MySQL",
                existingTopics = listOf("Indexes"),
                language = "ko",
                count = 3,
            ),
        ).thenAnswer {
            executorThread.set(Thread.currentThread().name)
            listOf("Transactions", "Replication", "Query optimizer")
        }
        val client = SystemOpenAIClient(
            executor = executor,
            keys = SystemOpenAIKeyProvider(properties),
            properties = properties,
        )

        val result = client.suggestTopics(
            rootTopic = "Databases",
            parentTopic = "MySQL",
            existingTopics = listOf("Indexes"),
            language = "ko",
            count = 3,
        )

        assertThat(result).containsExactly("Transactions", "Replication", "Query optimizer")
        assertThat(executorThread.get()).isNotEqualTo(callerThread)
        Mockito.verify(executor).suggestStudyTopics(
            apiKey = "system-key",
            model = "system-model",
            rootTopic = "Databases",
            parentTopic = "MySQL",
            existingTopics = listOf("Indexes"),
            language = "ko",
            count = 3,
        )
        Mockito.verifyNoMoreInteractions(executor)
    }

    @Test
    fun `content translation uses only the user-content OpenAI key and model`() = runBlocking {
        val properties = workloadProperties()
        val executor = Mockito.mock(OpenAIRequestExecutor::class.java)
        val translated = TranslatedQuestionContent("데이터베이스", "트랜잭션을 설명하세요.", null)
        Mockito.`when`(
            executor.translateQuestion(
                apiKey = "user-content-key",
                model = "user-content-model",
                topic = "Databases",
                question = "Explain transactions.",
                hint = null,
                sourceLanguage = "en",
                targetLanguage = "ko",
            ),
        ).thenReturn(translated)
        val client = OpenAIClient(
            executor = executor,
            keys = UserContentOpenAIKeyProvider(properties),
            properties = properties,
        )

        val result = client.translate(
            QuestionTranslationRequest(
                topic = "Databases",
                question = "Explain transactions.",
                hint = null,
                sourceLanguage = "en",
                targetLanguage = "ko",
            ),
        )

        assertThat(result).isEqualTo(translated)
        Mockito.verify(executor).translateQuestion(
            apiKey = "user-content-key",
            model = "user-content-model",
            topic = "Databases",
            question = "Explain transactions.",
            hint = null,
            sourceLanguage = "en",
            targetLanguage = "ko",
        )
        Mockito.verifyNoMoreInteractions(executor)
    }

    @Test
    fun `question generation cannot override the user-content OpenAI key`() = runBlocking {
        val properties = workloadProperties()
        val executor = Mockito.mock(OpenAIRequestExecutor::class.java)
        val client = OpenAIClient(
            executor = executor,
            keys = UserContentOpenAIKeyProvider(properties),
            properties = properties,
        )

        client.validate("caller-supplied-key")

        Mockito.verify(executor).validate("user-content-key")
        Mockito.verifyNoMoreInteractions(executor)
    }

    @Test
    fun `answer grading cannot override the user-content OpenAI key`() = runBlocking {
        val properties = workloadProperties()
        val executor = Mockito.mock(OpenAIRequestExecutor::class.java)
        val grade = GradedAnswer(
            score = 90,
            isCorrect = true,
            feedback = "Good answer.",
            explanation = "The answer covers the required concept.",
        )
        val onProgress: suspend (AiGradingStage) -> Unit = {}
        Mockito.`when`(
            executor.grade(
                apiKey = "user-content-key",
                model = "user-content-model",
                question = "What is a transaction?",
                answer = "An atomic unit of work.",
                topic = "Databases",
                level = 3,
                language = "en",
                rubric = null,
                onProgress = onProgress,
            ),
        ).thenReturn(grade)
        val client = OpenAIClient(
            executor = executor,
            keys = UserContentOpenAIKeyProvider(properties),
            properties = properties,
        )

        val result = client.gradeWithRubric(
            apiKey = "caller-supplied-key",
            model = "user-content-model",
            question = "What is a transaction?",
            answer = "An atomic unit of work.",
            topic = "Databases",
            level = 3,
            language = "en",
            rubric = null,
            onProgress = onProgress,
        )

        assertThat(result).isEqualTo(grade)
        Mockito.verify(executor).grade(
            apiKey = "user-content-key",
            model = "user-content-model",
            question = "What is a transaction?",
            answer = "An atomic unit of work.",
            topic = "Databases",
            level = 3,
            language = "en",
            rubric = null,
            onProgress = onProgress,
        )
        Mockito.verifyNoMoreInteractions(executor)
    }

    private fun workloadProperties() = BuddyStudyProperties(
        openai = BuddyStudyProperties.OpenAI(
            userContentApiKey = "user-content-key",
            systemApiKey = "system-key",
            model = "user-content-model",
            systemModel = "system-model",
        ),
    )
}
