import Foundation

struct AppErrorHandlingUseCase {
    func resolve(_ error: Error, fallback: String) -> AppErrorHandlingResolution {
        AppErrorHandlingPolicy.resolve(error, fallback: fallback)
    }

    func isAPIKeyError(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isAPIKeyError(error)
    }

    func isBackendDeviceNotFound(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isBackendDeviceNotFound(error)
    }

    func isUnauthorizedBackendError(_ error: Error) -> Bool {
        BackendErrorPresentationPolicy.isUnauthorizedBackendError(error)
    }

    func shouldResetBackendIdentity(after error: Error) -> Bool {
        BackendErrorPresentationPolicy.shouldResetBackendIdentity(after: error)
    }

    func diagnosticDescription(for error: Error) -> String {
        BackendErrorPresentationPolicy.diagnosticDescription(for: error)
    }
}
