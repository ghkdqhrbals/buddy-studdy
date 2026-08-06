import Foundation

@MainActor
struct AppUseCasesProvider {
    private let injectedBackendClient: RemotePushBackendClientProtocol?
    private let appDistributionContext: AppDistributionContext

    init(
        backendClient: RemotePushBackendClientProtocol? = nil,
        appDistributionContext: AppDistributionContext = .live
    ) {
        self.injectedBackendClient = backendClient
        self.appDistributionContext = appDistributionContext
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
            debugBackendBaseURL: debugBackendBaseURL,
            isTestFlight: appDistributionContext.isTestFlight
        ).displayBaseURL
    }

    func makeUseCases(
        isDebuggingEnabled: Bool,
        debugBackendBaseURL: String
    ) -> AppUseCases {
        AppUseCases(
            backendClient: injectedBackendClient ?? BackendBaseURLConfiguration(
                isDebuggingEnabled: isDebuggingEnabled,
                debugBackendBaseURL: debugBackendBaseURL,
                isTestFlight: appDistributionContext.isTestFlight
            ).makeClient()
        )
    }
}
