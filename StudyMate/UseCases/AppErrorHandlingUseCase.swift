import Foundation

struct AppErrorHandlingUseCase {
    func resolve(
        _ error: Error,
        fallback: String,
        language: AppLanguage? = nil
    ) -> AppErrorHandlingResolution {
        AppErrorHandlingPolicy.resolve(error, fallback: fallback, language: language)
    }

    func isAPIKeyError(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isAPIKeyError(error)
    }

    func isBackendDeviceNotFound(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isBackendDeviceNotFound(error)
    }

    func isBackendRecordNotFound(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isBackendRecordNotFound(error)
    }

    func isUnauthorizedBackendError(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isUnauthorizedBackendError(error)
    }

    func shouldResetBackendIdentity(after error: Error) -> Bool {
        BackendErrorPresentationPolicy.shouldResetBackendIdentity(after: error)
    }

    func shouldRefreshBackendAccessToken(after error: Error) -> Bool {
        BackendErrorPresentationPolicy.shouldRefreshBackendAccessToken(after: error)
    }

    func isPermanentBackendOperationError(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isPermanentBackendOperationError(error)
    }

    func diagnosticDescription(for error: Error) -> String {
        BackendErrorPresentationPolicy.diagnosticDescription(for: error)
    }
}
