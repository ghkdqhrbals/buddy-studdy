package com.buddystudy.backend.profile.adapter.inbound.web

import com.buddystudy.backend.profile.application.port.inbound.ReferralUseCase
import com.buddystudy.backend.profile.application.service.ReferralLinkProvider
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils

@RestController
class ReferralPublicController(
    private val referrals: ReferralPublicWebPort,
) {
    @GetMapping("/referrals/{code}", produces = [MediaType.TEXT_HTML_VALUE])
    suspend fun landing(@PathVariable code: String): ResponseEntity<String> = referrals.landing(code)

    @GetMapping(
        "/.well-known/apple-app-site-association",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun appleAppSiteAssociation(): AppleAppSiteAssociationResponse = referrals.appleAppSiteAssociation()
}

interface ReferralPublicWebPort {
    suspend fun landing(code: String): ResponseEntity<String>
    fun appleAppSiteAssociation(): AppleAppSiteAssociationResponse
}

@Component
class ReferralPublicWebAdapter(
    private val referrals: ReferralUseCase,
    private val links: ReferralLinkProvider,
) : ReferralPublicWebPort {
    override suspend fun landing(code: String): ResponseEntity<String> {
        val landing = referrals.landing(code)
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(
                renderLanding(
                    code = landing.code,
                    referralUrl = landing.referralUrl,
                    appDeepLink = landing.appDeepLink,
                    appStoreUrl = landing.appStoreUrl,
                    appStoreAppId = landing.appStoreAppId,
                ),
            )
    }

    override fun appleAppSiteAssociation(): AppleAppSiteAssociationResponse =
        AppleAppSiteAssociationResponse(
            applinks = AppleAppLinks(
                details = listOf(
                    AppleAppLinkDetails(
                        appID = links.appleAppId(),
                        paths = listOf("/referrals/*"),
                    ),
                ),
            ),
        )

    private fun renderLanding(
        code: String,
        referralUrl: String,
        appDeepLink: String,
        appStoreUrl: String,
        appStoreAppId: Long,
    ): String {
        val safeCode = HtmlUtils.htmlEscape(code)
        val safeReferralUrl = HtmlUtils.htmlEscape(referralUrl)
        val safeAppDeepLink = HtmlUtils.htmlEscape(appDeepLink)
        val safeAppStoreUrl = HtmlUtils.htmlEscape(appStoreUrl)
        return """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="robots" content="noindex,nofollow">
              <meta name="apple-itunes-app" content="app-id=$appStoreAppId, app-argument=$safeAppDeepLink">
              <link rel="canonical" href="$safeReferralUrl">
              <title>BuddyStudy 추천 초대</title>
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; background: #f6f7fb; color: #172033; }
                main { max-width: 32rem; margin: 12vh auto; padding: 2rem; text-align: center; }
                section { background: white; border-radius: 1.5rem; padding: 2.5rem 1.5rem; box-shadow: 0 1rem 3rem rgba(23,32,51,.08); }
                h1 { margin-top: 0; } p { line-height: 1.55; }
                input { box-sizing: border-box; width: 100%; padding: .7rem .8rem; border: 0; border-radius: .65rem; background: #eef1f8; text-align: center; font: 700 1rem ui-monospace, monospace; }
                a, button { box-sizing: border-box; width: 100%; display: block; margin-top: .8rem; padding: .9rem 1rem; border-radius: .8rem; text-decoration: none; font: 700 1rem -apple-system, BlinkMacSystemFont, sans-serif; cursor: pointer; }
                .primary { background: #4967e8; color: white; } .secondary { color: #4967e8; border: 1px solid #cad3fb; }
                button.secondary { background: white; }
              </style>
            </head>
            <body>
              <main><section>
                <h1>BuddyStudy에서 함께 공부해요</h1>
                <p>가입을 완료하면 초대한 친구와 가입한 친구 모두 Pro 한 달 혜택을 받아요.</p>
                <p>추천 코드</p>
                <input id="referral-code" value="$safeCode" readonly aria-label="추천 코드">
                <button id="copy-code" class="secondary" type="button">추천 코드 복사</button>
                <a class="primary" href="$safeAppDeepLink">BuddyStudy에서 열기</a>
                <a class="secondary" href="$safeAppStoreUrl">App Store에서 받기</a>
              </section></main>
              <script>
                const codeField = document.getElementById('referral-code');
                const copyButton = document.getElementById('copy-code');
                copyButton.addEventListener('click', async () => {
                  try {
                    await navigator.clipboard.writeText(codeField.value);
                    copyButton.textContent = '복사했어요';
                  } catch (_) {
                    codeField.focus();
                    codeField.select();
                  }
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}

data class AppleAppSiteAssociationResponse(
    val applinks: AppleAppLinks,
)

data class AppleAppLinks(
    val apps: List<String> = emptyList(),
    val details: List<AppleAppLinkDetails>,
)

data class AppleAppLinkDetails(
    val appID: String,
    val paths: List<String>,
)
