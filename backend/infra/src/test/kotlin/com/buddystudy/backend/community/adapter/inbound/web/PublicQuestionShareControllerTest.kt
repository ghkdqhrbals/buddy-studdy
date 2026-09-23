package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview
import com.buddystudy.backend.community.application.port.inbound.PublicQuestionShareUseCase
import com.buddystudy.backend.config.BuddyStudyProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class PublicQuestionShareControllerTest {
    private val reads = mutableListOf<Pair<Long, String>>()
    private val client = WebTestClient.bindToController(PublicQuestionShareController(PublicQuestionShareWebAdapter(
        object : PublicQuestionShareUseCase {
            override suspend fun preview(questionId: Long, language: String): PublicQuestionSharePreview? {
                reads += questionId to language
                return if (questionId == 42L) PublicQuestionSharePreview(42, "Swift <b>UI</b>", "<script>alert(1)</script> \"A&B\"", language) else null
            }
        }, BuddyStudyProperties(),
    ))).build()

    @Test
    fun `anonymous landing is localized with same question app links and escaped preview`() {
        mapOf("ko" to "여러분은 어떻게 답하시겠어요?", "en" to "What would your answer be?", "ja" to "あなたなら、どう答えますか？").forEach { (language, heading) ->
            client.get().uri("/questions/42?tl=$language").exchange()
                .expectStatus().isOk
                .expectHeader().contentTypeCompatibleWith("text/html")
                .expectHeader().valueEquals("Cache-Control", "no-store, max-age=0")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-Robots-Tag", "noindex, noarchive")
                .expectHeader().valueMatches("Content-Security-Policy", ".*default-src 'none'.*")
                .expectBody(String::class.java).consumeWith {
                    val html = it.responseBody.orEmpty()
                    assertThat(html).contains(heading, "<html lang=\"$language\">", "lang=\"$language\"")
                    assertThat(html).contains("https://api.ghkdqhrbals.org/questions/42?tl=$language")
                    assertThat(html).contains("https://apps.apple.com/app/id6774108938")
                    assertThat(html).contains("app-id=6774108938, app-argument=buddystudy://public/questions/42")
                    assertThat(html).contains("href=\"buddystudy://public/questions/42\"")
                    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "&quot;A&amp;B&quot;", "Swift &lt;b&gt;UI&lt;/b&gt;")
                    assertThat(html).doesNotContain("<script>", "<b>UI</b>")
                }
        }
        assertThat(reads).containsExactly(42L to "ko", 42L to "en", 42L to "ja")
    }

    @Test
    fun `unavailable private nonexistent and malformed ids return indistinguishable content`() {
        val bodies = listOf("41", "999", "-1", "0", "abc", "9223372036854775808").map { id ->
            client.get().uri("/questions/$id?tl=en").exchange().expectStatus().isNotFound
                .expectBody(String::class.java).returnResult().responseBody
        }
        assertThat(bodies.distinct()).hasSize(1)
        assertThat(bodies.first()).contains("This question is unavailable")
            .doesNotContain("og:", "apple-itunes-app", "public/questions/", "<script>")
        assertThat(reads).containsExactly(41L to "en", 999L to "en")
    }

    @Test
    fun `unsupported language falls back and app store id comes from configured release identity`() {
        val properties = BuddyStudyProperties().also { it.referral.appStoreAppId = 12345 }
        val adapter = PublicQuestionShareWebAdapter(object : PublicQuestionShareUseCase {
            override suspend fun preview(questionId: Long, language: String) = PublicQuestionSharePreview(questionId, "Topic", "Question", "ja")
        }, properties)
        WebTestClient.bindToController(PublicQuestionShareController(adapter)).build()
            .get().uri("/questions/42?tl=unsupported").exchange().expectStatus().isOk
            .expectBody(String::class.java).consumeWith {
                assertThat(it.responseBody).contains("<html lang=\"ko\">", "class=\"question-text\" lang=\"ja\"", "app-id=12345", "https://apps.apple.com/app/id12345", "?tl=ko")
            }
    }
}
