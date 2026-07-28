import Foundation
import PostHog

@MainActor
enum ProductAnalytics {
    private static let projectTokenInfoKey = "PostHogProjectToken"
    private static let hostInfoKey = "PostHogHost"
    private static let debugTestEventEnvironmentKey = "BUDDYSTUDY_POSTHOG_TEST_EVENT"

    private static var isStarted = false
    private static var identifiedUserID: String?

    static func start(bundle: Bundle = .main, processInfo: ProcessInfo = .processInfo) {
        guard !isStarted,
              let projectToken = configuredProjectToken(in: bundle),
              let host = configuredHost(in: bundle) else {
            return
        }

        let config = PostHogConfig(projectToken: projectToken, host: host.absoluteString)
        config.captureApplicationLifecycleEvents = true
        config.captureScreenViews = false
        config.captureElementInteractions = false
        config.rageClickConfig.enabled = false
        config.enableSwizzling = true
        config.sessionReplay = true
        config.sessionReplayConfig.screenshotMode = true
        config.sessionReplayConfig.maskAllTextInputs = true
        config.sessionReplayConfig.maskAllImages = true
        config.sessionReplayConfig.maskAllSandboxedViews = true
        config.sessionReplayConfig.captureLogs = false
        config.sessionReplayConfig.captureNetworkTelemetry = false
        let sessionReplaySampleRate: Double
        #if DEBUG
        sessionReplaySampleRate = processInfo.environment[debugTestEventEnvironmentKey] == "1" ? 1.0 : 0.1
        #else
        sessionReplaySampleRate = 0.1
        #endif
        config.sessionReplayConfig.sampleRate = NSNumber(value: sessionReplaySampleRate)
        config.surveys = false
        config.errorTrackingConfig.autoCapture = false
        config.preloadFeatureFlags = false
        config.sendFeatureFlagEvent = false
        config.personProfiles = .identifiedOnly
        config.flushAt = 10
        config.flushIntervalSeconds = 30

        #if DEBUG
        config.debug = true
        #endif

        PostHogSDK.shared.setup(config)
        isStarted = true

        #if DEBUG
        if processInfo.environment[debugTestEventEnvironmentKey] == "1" {
            PostHogSDK.shared.startSessionRecording(resumeCurrent: false)
            capture("analytics connection tested")
            PostHogSDK.shared.flush()
        }
        #endif
    }

    static func capture(_ event: String, properties: [String: Any] = [:]) {
        guard isStarted else {
            return
        }

        PostHogSDK.shared.capture(event, properties: properties)
    }

    static func screenViewed(_ tab: AppTab) {
        guard isStarted else {
            return
        }

        PostHogSDK.shared.screen(tab.analyticsName)
    }

    static func identify(userID: Int, provider: String, appLanguage: AppLanguage) {
        guard isStarted else {
            return
        }

        let distinctID = String(userID)
        guard identifiedUserID != distinctID else {
            return
        }

        PostHogSDK.shared.identify(
            distinctID,
            userProperties: [
                "login_provider": provider.lowercased(),
                "app_language": appLanguage.rawValue,
            ],
            userPropertiesSetOnce: [
                "platform": "ios",
            ]
        )
        identifiedUserID = distinctID
    }

    static func resetAfterSignOut() {
        guard isStarted else {
            return
        }

        PostHogSDK.shared.reset()
        identifiedUserID = nil
    }

    private static func configuredProjectToken(in bundle: Bundle) -> String? {
        guard let value = bundle.object(forInfoDictionaryKey: projectTokenInfoKey) as? String else {
            return nil
        }

        let token = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, !token.contains("$(") else {
            return nil
        }

        return token
    }

    private static func configuredHost(in bundle: Bundle) -> URL? {
        guard let value = bundle.object(forInfoDictionaryKey: hostInfoKey) as? String else {
            return nil
        }

        let host = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty,
              !host.contains("$("),
              let url = URL(string: host),
              url.scheme == "https",
              url.host != nil else {
            return nil
        }

        return url
    }
}

private extension AppTab {
    var analyticsName: String {
        switch self {
        case .home:
            "home"
        case .study:
            "study_room"
        case .settings:
            "settings"
        case .records:
            "records"
        case .statistics:
            "statistics"
        case .notifications:
            "notifications"
        }
    }
}
