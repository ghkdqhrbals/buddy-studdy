package com.buddystudy.backend.profile.application.service

import com.buddystudy.backend.config.BuddyStudyProperties
import com.buddystudy.backend.profile.application.model.ReferralLandingResponse
import org.springframework.stereotype.Component

@Component
class ReferralLinkProvider(
    private val properties: BuddyStudyProperties,
) {
    fun referralUrl(code: String): String = "${publicBaseUrl()}/referrals/$code"

    fun landing(code: String): ReferralLandingResponse = ReferralLandingResponse(
        code = code,
        referralUrl = referralUrl(code),
        appDeepLink = "buddystudy://referrals/$code",
        appStoreUrl = "https://apps.apple.com/app/id${properties.referral.appStoreAppId}",
        appStoreAppId = properties.referral.appStoreAppId,
    )

    fun appleAppId(): String = properties.referral.appleAppId

    private fun publicBaseUrl(): String = properties.referral.publicBaseUrl.trim().trimEnd('/')
}
