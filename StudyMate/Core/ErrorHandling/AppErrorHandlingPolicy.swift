import Foundation

struct AppErrorHandlingResolution: Equatable {
    var featureMessage: String?
    var shouldShowPopup: Bool
    var requiresLogin: Bool
    var isPageAccessDenied: Bool
    var requiresEmailVerification: Bool
    var shouldResetBackendIdentity: Bool
    var shouldClearFeatureMessage: Bool
}

enum AppErrorHandlingPolicy {
    static func resolve(_ error: Error, fallback: String) -> AppErrorHandlingResolution {
        let presentation = BackendErrorPresentationPolicy.presentation(for: error, fallback: fallback)
        let suppressFeatureMessage = presentation.requiresLogin ||
            presentation.isPageAccessDenied ||
            presentation.shouldResetBackendIdentity

        return AppErrorHandlingResolution(
            featureMessage: suppressFeatureMessage ? nil : presentation.inlineMessage,
            shouldShowPopup: suppressFeatureMessage ? false : presentation.shouldShowPopup,
            requiresLogin: presentation.requiresLogin,
            isPageAccessDenied: presentation.isPageAccessDenied,
            requiresEmailVerification: presentation.requiresEmailVerification,
            shouldResetBackendIdentity: presentation.shouldResetBackendIdentity,
            shouldClearFeatureMessage: suppressFeatureMessage
        )
    }
}
