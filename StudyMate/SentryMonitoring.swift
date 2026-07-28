import Foundation
import Sentry

enum SentryMonitoring {
    private static let dsnInfoKey = "SentryDSN"
    private static let debugTestEventEnvironmentKey = "BUDDYSTUDY_SENTRY_TEST_EVENT"

    static func start(bundle: Bundle = .main, processInfo: ProcessInfo = .processInfo) {
        guard let dsn = configuredDSN(in: bundle) else {
            return
        }

        SentrySDK.start { options in
            options.dsn = dsn
            options.environment = environmentName
            options.dist = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String

            options.sendDefaultPii = false
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            options.reportAccessibilityIdentifier = false
            options.enableLogs = false

            options.sessionReplay.sessionSampleRate = 0
            options.sessionReplay.onErrorSampleRate = 0

            options.enableNetworkBreadcrumbs = false
            options.enableNetworkTracking = false
            options.enableUserInteractionTracing = false
            options.enableUIViewControllerTracing = false
            options.enableSwizzling = false

            options.enableAutoSessionTracking = true
            options.enableAutoBreadcrumbTracking = true
            options.enableAppHangTracking = true
            options.appHangTimeoutInterval = 4
            options.tracesSampleRate = 0.1

            #if DEBUG
            options.debug = true
            #endif
        }

        #if DEBUG
        if processInfo.environment[debugTestEventEnvironmentKey] == "1" {
            SentrySDK.capture(message: "BuddyStudy Sentry debug connection test")
        }
        #endif
    }

    private static func configuredDSN(in bundle: Bundle) -> String? {
        guard let value = bundle.object(forInfoDictionaryKey: dsnInfoKey) as? String else {
            return nil
        }

        let dsn = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !dsn.isEmpty,
              !dsn.contains("$("),
              let url = URL(string: dsn),
              url.scheme == "https",
              url.host != nil else {
            return nil
        }

        return dsn
    }

    private static var environmentName: String {
        #if DEBUG
        "development"
        #else
        "production"
        #endif
    }
}
