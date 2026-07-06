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
