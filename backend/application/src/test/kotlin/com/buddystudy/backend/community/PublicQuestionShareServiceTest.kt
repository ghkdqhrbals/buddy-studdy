package com.buddystudy.backend.community

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionSharePort
import com.buddystudy.backend.community.application.service.PublicQuestionShareService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PublicQuestionShareServiceTest {
    @Test
    fun `preview is bounded localized and does not resolve invalid identifiers`(): Unit = runBlocking {
        val reads = mutableListOf<Pair<Long, String>>()
        val service = PublicQuestionShareService(object : PublicQuestionSharePort {
            override suspend fun findPreview(questionId: Long, language: String): PublicQuestionSharePreview? {
                reads += questionId to language
                return if (questionId == 7L) PublicQuestionSharePreview(7, "t".repeat(121), "q".repeat(721), "en") else null
            }
        })
        assertThat(service.preview(0, "en")).isNull()
        assertThat(service.preview(-1, "en")).isNull()
        assertThat(reads).isEmpty()
        val preview = service.preview(7, "en")!!
        assertThat(preview.topic).hasSize(120)
        assertThat(preview.question).hasSize(720)
        assertThat(preview.contentLanguage).isEqualTo("en")
        assertThat(service.preview(8, "unsupported")).isNull()
        assertThat(reads).containsExactly(7L to "en", 8L to "ko")
    }
}
