import Foundation

struct BackendErrorPresentation: Equatable {
    var message: String
    var inlineMessage: String?
    var shouldShowPopup: Bool
    var requiresLogin: Bool
    var isPageAccessDenied: Bool
    var requiresEmailVerification: Bool
    var shouldResetBackendIdentity: Bool
}

enum BackendErrorPresentationPolicy {
    static func presentation(for error: Error, fallback: String) -> BackendErrorPresentation {
        if let backendError = error as? RemotePushBackendError {
            return presentation(for: backendError, fallback: fallback)
        }

        let message = fallbackMessage(for: error, fallback: fallback)
        return BackendErrorPresentation(
            message: message,
            inlineMessage: message,
            shouldShowPopup: true,
            requiresLogin: false,
            isPageAccessDenied: false,
            requiresEmailVerification: false,
            shouldResetBackendIdentity: false
        )
    }

    static func presentation(for error: RemotePushBackendError, fallback: String) -> BackendErrorPresentation {
        let message = userFacingMessage(for: error, fallback: fallback)
        let shouldShowInlineError = shouldShowInlineError(for: error)
        return BackendErrorPresentation(
            message: message,
            inlineMessage: shouldShowInlineError ? message : nil,
            shouldShowPopup: shouldShowPopup(for: error),
            requiresLogin: requiresLogin(error),
            isPageAccessDenied: isPageAccessDenied(error),
            requiresEmailVerification: requiresEmailVerification(error),
            shouldResetBackendIdentity: shouldResetBackendIdentity(after: error)
        )
    }

    static func isAPIKeyError(_ error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError else {
            return false
        }

        switch backendError {
        case .httpStatus(let status, let body, let apiError):
            let lowercasedBody = (apiError?.message ?? body).lowercased()
            let code = apiError?.code ?? ""
            return status == 401
                || status == 403
                || code.contains("OPENAI_API_KEY")
                || lowercasedBody.contains("api key")
                || lowercasedBody.contains("unauthorized")
        case .invalidResponse:
            return false
        }
    }

    static func isBackendDeviceNotFound(_ error: Error) -> Bool {
        (error as? RemotePushBackendError)?.backendCode == "DEVICE_NOT_FOUND"
    }

    static func isUnauthorizedBackendError(_ error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError else {
            return false
        }

        switch backendError {
        case .httpStatus(let status, _, let apiError):
            return status == 401
                || apiError?.code == "AUTH_ACCESS_TOKEN_REQUIRED"
                || apiError?.code == "AUTH_INVALID_ACCESS_TOKEN"
        case .invalidResponse:
            return false
        }
    }

    static func shouldResetBackendIdentity(after error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError else {
            return false
        }

        switch backendError {
        case .httpStatus(let status, _, let apiError):
            let code = apiError?.code
            return status == 401
                || code == "AUTH_ACCESS_TOKEN_REQUIRED"
                || code == "AUTH_INVALID_ACCESS_TOKEN"
                || code == "AUTH_DEVICE_CREDENTIALS_REQUIRED"
                || code == "AUTH_INVALID_DEVICE_CREDENTIALS"
                || code == "AUTH_DEVICE_MISMATCH"
                || code == "DEVICE_NOT_FOUND"
        case .invalidResponse:
            return false
        }
    }

    static func requiresLogin(_ error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(_, _, let apiError):
            guard let code = apiError?.code else {
                return false
            }
            return loginRequiredCodes.contains(code)
        case .invalidResponse:
            return false
        }
    }

    static func isPageAccessDenied(_ error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(let statusCode, _, let apiError):
            return statusCode == 401
                || requiresLogin(error)
                || apiError?.code == "PAGE_ACCESS_DENIED"
                || apiError?.code == "ACCOUNT_FORBIDDEN"
        case .invalidResponse:
            return false
        }
    }

    static func requiresEmailVerification(_ error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(_, _, let apiError):
            guard let apiError else {
                return false
            }

            if apiError.code == "AUTH_EMAIL_VERIFICATION_REQUIRED" {
                return true
            }

            return apiError.code == "AUTH_GOOGLE_REQUIRED"
                && apiError.message.localizedCaseInsensitiveContains("verification code")
        case .invalidResponse:
            return false
        }
    }

    static func shouldShowPopup(for error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(_, _, let apiError):
            guard let code = apiError?.code else {
                return true
            }
            return !suppressedPopupCodes.contains(code)
        case .invalidResponse:
            return true
        }
    }

    static func shouldShowInlineError(for error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(_, _, let apiError):
            guard let code = apiError?.code else {
                return true
            }
            return !suppressedInlineCodes.contains(code)
        case .invalidResponse:
            return true
        }
    }

    static func userFacingMessage(for error: RemotePushBackendError, fallback: String) -> String {
        switch error {
        case .httpStatus(_, _, let apiError):
            if let message = apiError?.message.trimmingCharacters(in: .whitespacesAndNewlines),
               !message.isEmpty {
                return message
            }
        case .invalidResponse:
            break
        }

        let localized = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        return localized.isEmpty ? fallback : localized
    }

    private static func fallbackMessage(for error: Error, fallback: String) -> String {
        let localized = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !localized.isEmpty else {
            return fallback
        }
        return localized
    }

    private static let suppressedPopupCodes: Set<String> = [
        "AUTH_ACCESS_TOKEN_REQUIRED",
        "AUTH_DEVICE_CREDENTIALS_REQUIRED",
        "AUTH_DEVICE_MISMATCH",
        "AUTH_INVALID_ACCESS_TOKEN",
        "AUTH_INVALID_DEVICE_CREDENTIALS",
        "DEVICE_NOT_FOUND",
    ]

    private static let suppressedInlineCodes: Set<String> = suppressedPopupCodes

    private static let loginRequiredCodes: Set<String> = [
        "AUTH_ACCESS_TOKEN_REQUIRED",
        "AUTH_DEVICE_CREDENTIALS_REQUIRED",
        "AUTH_DEVICE_MISMATCH",
        "AUTH_GOOGLE_REQUIRED",
        "AUTH_INVALID_ACCESS_TOKEN",
        "AUTH_INVALID_DEVICE_CREDENTIALS",
        "DEVICE_NOT_FOUND",
        "PERMISSION_DENIED",
    ]
}

extension RemotePushBackendError {
    var requiresLogin: Bool {
        BackendErrorPresentationPolicy.requiresLogin(self)
    }

    var isPageAccessDenied: Bool {
        BackendErrorPresentationPolicy.isPageAccessDenied(self)
    }

    var requiresEmailVerification: Bool {
        BackendErrorPresentationPolicy.requiresEmailVerification(self)
    }

    var shouldShowPopup: Bool {
        BackendErrorPresentationPolicy.shouldShowPopup(for: self)
    }

    var shouldShowInlineError: Bool {
        BackendErrorPresentationPolicy.shouldShowInlineError(for: self)
    }

    func userFacingMessage(fallback: String) -> String {
        BackendErrorPresentationPolicy.userFacingMessage(for: self, fallback: fallback)
    }

    func presentation(fallback: String) -> BackendErrorPresentation {
        BackendErrorPresentationPolicy.presentation(for: self, fallback: fallback)
    }
}
