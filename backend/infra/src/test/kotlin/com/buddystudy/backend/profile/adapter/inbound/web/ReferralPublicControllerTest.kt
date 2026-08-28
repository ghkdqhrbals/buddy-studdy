package com.buddystudy.backend.profile.adapter.inbound.web

import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.model.ReferralLandingResponse
import com.buddystudy.backend.profile.application.model.ReferralSummaryResponse
import com.buddystudy.backend.profile.application.port.inbound.ReferralUseCase
import com.buddystudy.backend.profile.application.service.ReferralLinkProvider
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

class ReferralPublicControllerTest {
    private val properties = BuddyStudyProperties()
    private val controller = ReferralPublicController(
        ReferralPublicWebAdapter(FakeReferralUseCase(), ReferralLinkProvider(properties)),
    )
    private val client = WebTestClient.bindToController(controller).build()

    @Test
    fun `landing includes app open store smart banner and clipboard fallback`() {
        client.get()
            .uri("/referrals/BS-ABCDEFGH")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith("text/html")
            .expectBody(String::class.java)
            .consumeWith { response ->
                val html = response.responseBody.orEmpty()
                check(html.contains("app-id=6774108938, app-argument=buddystudy://referrals/BS-ABCDEFGH"))
                check(html.contains("name=\"robots\" content=\"noindex,nofollow\""))
                check(html.contains("Pro 한 달"))
                check(html.contains("id=\"copy-code\""))
                check(html.contains("navigator.clipboard.writeText(codeField.value)"))
                check(html.contains("https://apps.apple.com/app/id6774108938"))
                check(html.contains("value=\"BS-ABCDEFGH\""))
            }
    }

    @Test
    fun `apple app site association exposes the referral universal link`() {
        client.get()
            .uri("/.well-known/apple-app-site-association")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith("application/json")
            .expectBody()
            .jsonPath("$.applinks.apps").isArray
            .jsonPath("$.applinks.details[0].appID")
            .isEqualTo("4CL25TC734.io.github.ghkdqhrbals.StudyMate")
            .jsonPath("$.applinks.details[0].paths[0]").isEqualTo("/referrals/*")
    }

    private class FakeReferralUseCase : ReferralUseCase {
        override suspend fun summary(principal: Principal): ReferralSummaryResponse = error("Not used")
        override suspend fun redeem(principal: Principal, code: String): ReferralSummaryResponse = error("Not used")

        override suspend fun landing(code: String): ReferralLandingResponse = ReferralLandingResponse(
            code = code,
            referralUrl = "https://api.ghkdqhrbals.org/referrals/$code",
            appDeepLink = "buddystudy://referrals/$code",
            appStoreUrl = "https://apps.apple.com/app/id6774108938",
            appStoreAppId = 6774108938,
        )
    }
}
