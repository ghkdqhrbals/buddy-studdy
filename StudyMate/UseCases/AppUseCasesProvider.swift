import Foundation

@MainActor
struct AppUseCasesProvider {
    private let injectedBackendClient: RemotePushBackendClientProtocol?

    init(backendClient: RemotePushBackendClientProtocol? = nil) {
        self.injectedBackendClient = backendClient
    }

    var usesConfigurableBackendClient: Bool {
        injectedBackendClient == nil
    }

    func normalizedDebugBackendBaseURL(_ value: String) -> String {
        BackendBaseURLConfiguration.normalizedDebugBackendBaseURL(value)
    }

    func resolvedDebugBackendURL(from value: String) -> URL? {
        BackendBaseURLConfiguration.resolvedDebugBackendURL(from: value)
    }

    func displayBaseURL(
        isDebuggingEnabled: Bool,
        debugBackendBaseURL: String
    ) -> String {
        BackendBaseURLConfiguration(
            isDebuggingEnabled: isDebuggingEnabled,
            debugBackendBaseURL: debugBackendBaseURL
        ).displayBaseURL
    }

    func makeUseCases(
        isDebuggingEnabled: Bool,
        debugBackendBaseURL: String
    ) -> AppUseCases {
        AppUseCases(
            backendClient: injectedBackendClient ?? BackendBaseURLConfiguration(
                isDebuggingEnabled: isDebuggingEnabled,
                debugBackendBaseURL: debugBackendBaseURL
            ).makeClient()
        )
    }
}
