import Foundation

struct BackendErrorPresentation: Equatable {
    var message: String
    var inlineMessage: String?
    var shouldShowPopup: Bool
    var requiresLogin: Bool
    var isPageAccessDenied: Bool
    var requiresEmailVerification: Bool
    var requiresTermsAgreement: Bool
    var isQuotaExceeded: Bool
    var isPendingQuestionConflict: Bool
    var shouldResetBackendIdentity: Bool
}

enum BackendErrorPresentationPolicy {
    static func presentation(
        for error: Error,
        fallback: String,
        language: AppLanguage? = nil
    ) -> BackendErrorPresentation {
        if let backendError = error as? RemotePushBackendError {
            return presentation(for: backendError, fallback: fallback, language: language)
        }

        if isCancellationLike(error) {
            return BackendErrorPresentation(
                message: "",
                inlineMessage: nil,
                shouldShowPopup: false,
                requiresLogin: false,
                isPageAccessDenied: false,
                requiresEmailVerification: false,
                requiresTermsAgreement: false,
                isQuotaExceeded: false,
                isPendingQuestionConflict: false,
                shouldResetBackendIdentity: false
            )
        }

        let message = fallbackMessage(for: error, fallback: fallback, language: language)
        return BackendErrorPresentation(
            message: message,
            inlineMessage: message,
            shouldShowPopup: false,
            requiresLogin: false,
            isPageAccessDenied: false,
            requiresEmailVerification: false,
            requiresTermsAgreement: false,
            isQuotaExceeded: false,
            isPendingQuestionConflict: false,
            shouldResetBackendIdentity: false
        )
    }

    static func presentation(
        for error: RemotePushBackendError,
        fallback: String,
        language: AppLanguage? = nil
    ) -> BackendErrorPresentation {
        let message = userFacingMessage(for: error, fallback: fallback, language: language)
        let requiresEmailVerification = requiresEmailVerification(error)
        let requiresTermsAgreement = requiresTermsAgreement(error)
        let requiresLogin = requiresEmailVerification ? false : requiresLogin(error)
        let isPageAccessDenied = requiresEmailVerification ? false : isPageAccessDenied(error)
        let shouldResetBackendIdentity = requiresEmailVerification ? false : shouldResetBackendIdentity(after: error)
        let shouldShowInlineError = shouldShowInlineError(for: error)
        return BackendErrorPresentation(
            message: message,
            inlineMessage: shouldShowInlineError ? message : nil,
            shouldShowPopup: shouldShowPopup(for: error),
            requiresLogin: requiresLogin,
            isPageAccessDenied: isPageAccessDenied,
            requiresEmailVerification: requiresEmailVerification,
            requiresTermsAgreement: requiresTermsAgreement,
            isQuotaExceeded: error.backendCode == "QUOTA_EXCEEDED",
            isPendingQuestionConflict: isPendingQuestionConflict(error),
            shouldResetBackendIdentity: shouldResetBackendIdentity
        )
    }

    static func isPendingQuestionConflict(_ error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError else {
            return false
        }

        switch backendError {
        case .httpStatus(_, let body, let apiError):
            if apiError?.code == "STUDY_PENDING_QUESTION_EXISTS" {
                return true
            }

            guard apiError?.code == "VALIDATION_ERROR" else {
                return false
            }
            let diagnostic = [
                apiError?.debugDescription,
                apiError?.description,
                body
            ]
                .compactMap { $0 }
                .joined(separator: " ")
                .lowercased()
            return diagnostic.contains("pending question already exists")
        case .invalidResponse:
            return false
        }
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
        case .httpStatus(_, _, let apiError):
            let code = apiError?.code
            return code == "AUTH_DEVICE_CREDENTIALS_REQUIRED"
                || code == "AUTH_INVALID_DEVICE_CREDENTIALS"
                || code == "AUTH_DEVICE_MISMATCH"
                || code == "DEVICE_NOT_FOUND"
        case .invalidResponse:
            return false
        }
    }

    static func shouldRefreshBackendAccessToken(after error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError else {
            return false
        }

        switch backendError {
        case .httpStatus(let status, _, let apiError):
            guard !shouldResetBackendIdentity(after: error) else {
                return false
            }
            return status == 401
                || apiError?.code == "AUTH_ACCESS_TOKEN_REQUIRED"
                || apiError?.code == "AUTH_INVALID_ACCESS_TOKEN"
        case .invalidResponse:
            return false
        }
    }

    static func requiresLogin(_ error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(let statusCode, _, let apiError):
            if apiError?.code == "AUTH_INVALID_EMAIL_CREDENTIALS" {
                return false
            }
            if statusCode == 401 {
                return true
            }
            return apiError.map(requiresLogin) ?? false
        case .invalidResponse:
            return false
        }
    }

    static func isPageAccessDenied(_ error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(let statusCode, _, let apiError):
            if apiError?.code == "AUTH_INVALID_EMAIL_CREDENTIALS" {
                return false
            }
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

    static func requiresTermsAgreement(_ error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(_, let body, let apiError):
            if let apiError {
                return apiError.code == "TERMS_AGREEMENT_REQUIRED"
                    || apiError.code == "TERMS_REAGREEMENT_REQUIRED"
                    || !(apiError.requiredTerms ?? []).isEmpty
            }
            let normalizedBody = body.uppercased()
            return normalizedBody.contains("TERMS_AGREEMENT_REQUIRED")
                || normalizedBody.contains("TERMS_REAGREEMENT_REQUIRED")
        case .invalidResponse:
            return false
        }
    }

    static func shouldShowPopup(for error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(let statusCode, _, let apiError):
            if statusCode == 401 {
                return false
            }
            guard let apiError else {
                return true
            }
            if requiresTermsAgreement(error) {
                return false
            }
            return !suppressesUserMessage(apiError)
        case .invalidResponse:
            return true
        }
    }

    static func shouldShowInlineError(for error: RemotePushBackendError) -> Bool {
        switch error {
        case .httpStatus(let statusCode, _, let apiError):
            if apiError?.code == "AUTH_INVALID_EMAIL_CREDENTIALS" {
                return true
            }
            if statusCode == 401 {
                return false
            }
            guard let apiError else {
                return true
            }
            if requiresTermsAgreement(error) {
                return false
            }
            return !suppressesUserMessage(apiError)
        case .invalidResponse:
            return true
        }
    }

    static func userFacingMessage(
        for error: RemotePushBackendError,
        fallback: String,
        language: AppLanguage? = nil
    ) -> String {
        switch error {
        case .httpStatus(let statusCode, _, let apiError):
            if isTransientInfrastructureStatus(statusCode) {
                return nonemptyFallback(
                    fallback,
                    defaultMessage: transientServiceMessage(language: language)
                )
            }

            if let message = apiError?.message.trimmingCharacters(in: .whitespacesAndNewlines),
               !message.isEmpty {
                if apiError?.code == "QUOTA_EXCEEDED",
                   let language,
                   let resetAt = apiError?.metadata?.quotaResetDate {
                    return AppStrings(language: language).monthlyQuotaExceededMessage(
                        serverMessage: message,
                        resetAt: resetAt
                    )
                }
                return message
            }
        case .invalidResponse:
            return nonemptyFallback(
                fallback,
                defaultMessage: invalidResponseMessage(language: language)
            )
        }

        return nonemptyFallback(
            fallback,
            defaultMessage: transientServiceMessage(language: language)
        )
    }

    static func shouldClearFeatureMessage(for error: Error) -> Bool {
        isCancellationLike(error)
    }

    static func isPermanentBackendOperationError(_ error: Error) -> Bool {
        error is RemotePushBackendError || error is DecodingError
    }

    static func diagnosticDescription(for error: Error) -> String {
        switch error {
        case DecodingError.keyNotFound(let key, let context):
            return decodingDiagnostic(
                kind: "keyNotFound",
                path: context.codingPath,
                key: key,
                context: context
            )
        case DecodingError.valueNotFound(let type, let context):
            return decodingDiagnostic(
                kind: "valueNotFound type=\(String(describing: type))",
                path: context.codingPath,
                context: context
            )
        case DecodingError.typeMismatch(let type, let context):
            return decodingDiagnostic(
                kind: "typeMismatch type=\(String(describing: type))",
                path: context.codingPath,
                context: context
            )
        case DecodingError.dataCorrupted(let context):
            return decodingDiagnostic(
                kind: "dataCorrupted",
                path: context.codingPath,
                context: context
            )
        default:
            return error.localizedDescription
        }
    }

    private static func fallbackMessage(
        for error: Error,
        fallback: String,
        language: AppLanguage?
    ) -> String {
        let strings = AppStrings(language: language ?? .korean)

        if error is DecodingError {
            return strings.responseDataUnreadable
        }

        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost:
                return strings.networkUnavailableRetry
            case .timedOut:
                return strings.requestTimedOutRetry
            default:
                break
            }
        }

        let localized = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !localized.isEmpty else {
            return fallback
        }
        return localized
    }

    private static func isTransientInfrastructureStatus(_ statusCode: Int) -> Bool {
        statusCode == 408 || statusCode == 429 || (500...599).contains(statusCode)
    }

    private static func nonemptyFallback(_ fallback: String, defaultMessage: String) -> String {
        let trimmedFallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedFallback.isEmpty ? defaultMessage : trimmedFallback
    }

    private static func transientServiceMessage(language: AppLanguage?) -> String {
        AppStrings(language: language ?? .korean).serviceTemporarilyUnavailable
    }

    private static func invalidResponseMessage(language: AppLanguage?) -> String {
        AppStrings(language: language ?? .korean).invalidServerResponse
    }

    private static func decodingDiagnostic(
        kind: String,
        path: [CodingKey],
        key: CodingKey? = nil,
        context: DecodingError.Context
    ) -> String {
        let codingPath = codingPathDescription(path, appending: key)
        let underlying = context.underlyingError.map { " underlying=\($0.localizedDescription)" } ?? ""
        return "decoding_error kind=\(kind) path=\(codingPath) detail=\(context.debugDescription)\(underlying)"
    }

    private static func codingPathDescription(_ path: [CodingKey], appending key: CodingKey?) -> String {
        (path + [key].compactMap { $0 }).reduce(into: "$") { result, component in
            if let index = component.intValue {
                result += "[\(index)]"
            } else {
                result += ".\(component.stringValue)"
            }
        }
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

    private static func requiresLogin(_ apiError: BackendAPIError) -> Bool {
        loginRequiredCodes.contains(apiError.code) ||
            isAuthNumericCode(apiError.numericCode)
    }

    private static func suppressesUserMessage(_ apiError: BackendAPIError) -> Bool {
        if apiError.code == "AUTH_INVALID_EMAIL_CREDENTIALS" {
            return false
        }
        return suppressedPopupCodes.contains(apiError.code) ||
            isAuthNumericCode(apiError.numericCode)
    }

    private static func isAuthNumericCode(_ code: Int?) -> Bool {
        guard let code else {
            return false
        }
        return (100..<200).contains(code)
    }

    private static func isCancellationLike(_ error: Error) -> Bool {
        if error is CancellationError {
            return true
        }

        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            return true
        }

        return error.localizedDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .localizedCaseInsensitiveContains("cancelled")
    }
}
