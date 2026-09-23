package com.buddystudy.backend.community.adapter.inbound.web

import com.buddystudy.backend.community.application.model.PublicQuestionSharePreview
import com.buddystudy.backend.community.application.port.inbound.PublicQuestionShareUseCase
import com.buddystudy.backend.config.BuddyStudyProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils

@RestController
class PublicQuestionShareController(private val sharing: PublicQuestionShareWebPort) {
    @GetMapping("/questions/{id}", produces = [MediaType.TEXT_HTML_VALUE])
    suspend fun landing(@PathVariable id: String, @RequestParam(defaultValue = "ko") tl: String) =
        sharing.landing(id, tl)
}

interface PublicQuestionShareWebPort {
    suspend fun landing(id: String, language: String): ResponseEntity<String>
}

@Component
class PublicQuestionShareWebAdapter(
    private val sharing: PublicQuestionShareUseCase,
    private val properties: BuddyStudyProperties,
) : PublicQuestionShareWebPort {
    override suspend fun landing(id: String, language: String): ResponseEntity<String> {
        val locale = language.takeIf { it in setOf("ko", "en", "ja") } ?: "ko"
        val questionId = id.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toLongOrNull()
        val preview = questionId?.let { sharing.preview(it, locale) }
        val body = if (preview == null) unavailable(locale) else render(preview, locale)
        return ResponseEntity.status(if (preview == null) HttpStatus.NOT_FOUND else HttpStatus.OK)
            .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            // A link can become private. Never retain public content in our browser/CDN cache.
            .header("Cache-Control", "no-store, max-age=0")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header("X-Frame-Options", "DENY")
            .header("X-Robots-Tag", "noindex, noarchive")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
            .body(body)
    }

    private fun render(preview: PublicQuestionSharePreview, language: String): String {
        val text = copy(language)
        val appId = properties.referral.appStoreAppId
        val storeUrl = "https://apps.apple.com/app/id$appId"
        val canonical = "${properties.referral.publicBaseUrl.trim().trimEnd('/')}/questions/${preview.id}?tl=$language"
        val appUrl = "buddystudy://public/questions/${preview.id}"
        val topic = escape(preview.topic)
        val title = escape("${preview.topic} · BuddyStudy")
        val description = escape(preview.question.take(160).replace(Regex("\\s+"), " "))
        val contentLanguage = preview.contentLanguage.takeIf { it in setOf("ko", "en", "ja") } ?: language
        return document(language, title, """
            <meta name="apple-itunes-app" content="app-id=$appId, app-argument=${escape(appUrl)}">
            <link rel="canonical" href="${escape(canonical)}">
            <meta name="description" content="$description">
            <meta property="og:type" content="website">
            <meta property="og:site_name" content="BuddyStudy">
            <meta property="og:title" content="$title">
            <meta property="og:description" content="$description">
            <meta property="og:url" content="${escape(canonical)}">
            <meta name="twitter:card" content="summary">
        """.trimIndent(), """
            <p class="eyebrow">BuddyStudy <span aria-hidden="true">/</span> ${text.label}</p>
            <h1>${text.headline}</h1>
            <section class="question" aria-label="${text.label}">
              <p class="topic">$topic</p>
              <p class="question-text" lang="$contentLanguage">${escape(preview.question)}</p>
            </section>
            <p class="body-copy">${text.description}</p>
            <a class="primary" href="${escape(storeUrl)}">${text.install} <span aria-hidden="true">↗</span></a>
            <a class="secondary" href="${escape(appUrl)}">${text.open}</a>
            <p class="footnote">${text.afterInstall}</p>
        """.trimIndent())
    }

    private fun unavailable(language: String): String {
        val text = copy(language)
        // Identical missing/private response: no question ID, preview, canonical or social metadata.
        return document(language, "BuddyStudy", "", """
            <p class="eyebrow">BuddyStudy</p>
            <h1>${text.unavailable}</h1>
            <p class="body-copy">${text.unavailableDetail}</p>
        """.trimIndent())
    }

    private fun document(language: String, title: String, metadata: String, content: String) = """
        <!doctype html>
        <html lang="$language"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex,noarchive"><title>$title</title>
        $metadata
        <style>
          :root { color-scheme: light dark; --bg:#f5f6f1; --card:#fff; --ink:#152c23; --muted:#52675d; --line:#dce4dc; --accent:#176846; }
          * { box-sizing:border-box; } body { margin:0; padding:28px 20px; background:var(--bg); color:var(--ink); font:17px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
          main { width:100%; max-width:520px; margin:7vh auto; } .eyebrow { font-size:13px; font-weight:700; letter-spacing:.04em; color:var(--muted); } .eyebrow span { margin:0 8px; opacity:.45; }
          h1 { font-size:clamp(28px,7vw,40px); letter-spacing:-.04em; line-height:1.2; margin:20px 0 28px; }
          .question { padding:24px; background:var(--card); border:1px solid var(--line); border-radius:20px; }
          .topic { color:var(--accent); font-size:13px; font-weight:700; margin:0 0 12px; overflow-wrap:anywhere; }
          .question-text { margin:0; white-space:pre-wrap; word-break:keep-all; overflow-wrap:anywhere; font-size:20px; line-height:1.65; }
          .body-copy { margin:24px 0; color:var(--muted); word-break:keep-all; } a { display:block; border-radius:12px; padding:14px 18px; text-align:center; text-decoration:none; font-weight:650; }
          .primary { background:var(--accent); color:#fff; } .secondary { margin-top:10px; color:var(--accent); border:1px solid var(--line); }
          a:focus-visible { outline:3px solid var(--accent); outline-offset:4px; } .footnote { font-size:12px; color:var(--muted); text-align:center; margin:20px 10px; }
          @media(prefers-color-scheme:dark) { :root { --bg:#101b16; --card:#192820; --ink:#e9f2ea; --muted:#aabfb1; --line:#30473a; --accent:#58ba87; } .primary { color:#102819; } }
        </style></head><body><main>$content</main></body></html>
    """.trimIndent()

    private fun escape(value: String) = HtmlUtils.htmlEscape(value, "UTF-8")

    private fun copy(language: String): LandingCopy = when (language) {
        "en" -> LandingCopy("A question to think about", "What would your answer be?", "Explore public questions, follow your interests, and practice with AI feedback in BuddyStudy.", "Get BuddyStudy on the App Store", "Open this question in BuddyStudy", "After installing, return to this link to open the same question.", "This question is unavailable", "It may no longer be public. You can explore other questions in BuddyStudy.")
        "ja" -> LandingCopy("考えてみたい質問", "あなたなら、どう答えますか？", "BuddyStudyで公開質問を読み、興味のあるトピックをフォロー。AIのフィードバックで学びを深めましょう。", "App StoreでBuddyStudyを入手", "BuddyStudyでこの質問を開く", "インストール後、このリンクに戻ると同じ質問を開けます。", "この質問は表示できません", "現在は公開されていない可能性があります。BuddyStudyでほかの質問を探せます。")
        else -> LandingCopy("함께 생각해 볼 질문", "여러분은 어떻게 답하시겠어요?", "BuddyStudy에서 공개 질문을 둘러보고, 관심 주제를 구독하고, AI 피드백으로 공부를 이어가세요.", "App Store에서 BuddyStudy 받기", "BuddyStudy에서 이 질문 열기", "설치 후 이 링크로 돌아오면 같은 질문을 열 수 있어요.", "이 질문을 볼 수 없어요", "현재 공개되지 않은 질문일 수 있어요. BuddyStudy에서 다른 질문을 살펴보세요.")
    }

    private data class LandingCopy(val label: String, val headline: String, val description: String, val install: String, val open: String, val afterInstall: String, val unavailable: String, val unavailableDetail: String)
}
