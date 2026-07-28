import Foundation

struct AppErrorHandlingResolution: Equatable {
    var featureMessage: String?
    var serviceAvailability: BackendServiceAvailability?
    var shouldShowPopup: Bool
    var requiresLogin: Bool
    var isPageAccessDenied: Bool
    var requiresEmailVerification: Bool
    var requiresTermsAgreement: Bool
    var isQuotaExceeded: Bool
    var isPendingQuestionConflict: Bool
    var shouldResetBackendIdentity: Bool
    var shouldClearFeatureMessage: Bool
}

enum AppErrorHandlingPolicy {
    static func resolve(
        _ error: Error,
        fallback: String,
        language: AppLanguage? = nil
    ) -> AppErrorHandlingResolution {
        let presentation = BackendErrorPresentationPolicy.presentation(
            for: error,
            fallback: fallback,
            language: language
        )
        let suppressFeatureMessage = presentation.requiresLogin ||
            presentation.isPageAccessDenied ||
            presentation.requiresTermsAgreement ||
            presentation.shouldResetBackendIdentity
        let shouldClearFeatureMessage = suppressFeatureMessage ||
            BackendErrorPresentationPolicy.shouldClearFeatureMessage(for: error)

        return AppErrorHandlingResolution(
            featureMessage: suppressFeatureMessage ? nil : presentation.inlineMessage,
            serviceAvailability: BackendErrorPresentationPolicy.serviceAvailability(for: error),
            shouldShowPopup: false,
            requiresLogin: presentation.requiresLogin,
            isPageAccessDenied: presentation.isPageAccessDenied,
            requiresEmailVerification: presentation.requiresEmailVerification,
            requiresTermsAgreement: presentation.requiresTermsAgreement,
            isQuotaExceeded: presentation.isQuotaExceeded,
            isPendingQuestionConflict: presentation.isPendingQuestionConflict,
            shouldResetBackendIdentity: presentation.shouldResetBackendIdentity,
            shouldClearFeatureMessage: shouldClearFeatureMessage
        )
    }
}
