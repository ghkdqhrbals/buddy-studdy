import Foundation
import OSLog

#if canImport(FirebaseAnalytics) && canImport(FirebaseCore)
import FirebaseAnalytics
import FirebaseCore
#endif

enum AppAnalyticsScreen: String {
    case onboarding
    case home
    case studyTree = "study_tree"
    case studyRoom = "study_room"
    case records
    case recordsLogin = "records_login"
    case statistics
    case statisticsLogin = "statistics_login"
    case notifications
    case login
    case profile
    case settings
}

enum AppAnalyticsLoginMethod: String {
    case google
    case email
}

enum AppAnalyticsLoginOutcome: String {
    case started
    case completed
    case failed
    case cancelled
    case verificationRequired = "verification_required"
}

enum AppAnalyticsStudyKind: String {
    case root
    case topic
}

enum AppAnalyticsQuestionSource: String {
    case manual
    case scheduled
}

enum AppAnalyticsNotificationKind: String {
    case question
    case community
    case general
}

struct AppAnalyticsConfiguration {
    static func isUsable(
        dictionary: [String: Any],
        bundleIdentifier: String?
    ) -> Bool {
        guard let bundleIdentifier,
              let configuredBundleID = dictionary["BUNDLE_ID"] as? String,
              configuredBundleID == bundleIdentifier,
              let appID = dictionary["GOOGLE_APP_ID"] as? String,
              appID.hasPrefix("1:"),
              let apiKey = dictionary["API_KEY"] as? String,
              !apiKey.isEmpty,
              !apiKey.hasPrefix("NOT_CONFIGURED"),
              let analyticsEnabled = dictionary["IS_ANALYTICS_ENABLED"] as? Bool,
              analyticsEnabled else {
            return false
        }

        return true
    }
}

@MainActor
enum AppAnalytics {
    private static let logger = Logger(
        subsystem: "io.github.ghkdqhrbals.StudyMate",
        category: "analytics"
    )
    private static var isConfigured = false

    static func start(
        bundle: Bundle = .main,
        processInfo: ProcessInfo = .processInfo
    ) {
        guard !isConfigured else {
            return
        }

        #if canImport(FirebaseAnalytics) && canImport(FirebaseCore)
        #if DEBUG
        guard processInfo.environment["BUDDYSTUDY_GA_DEBUG"] == "1" else {
            logger.notice("Firebase Analytics collection is disabled for debug builds.")
            return
        }
        #endif

        guard let configurationURL = bundle.url(
            forResource: "GoogleService-Info",
            withExtension: "plist"
        ),
        let dictionary = NSDictionary(contentsOf: configurationURL) as? [String: Any],
        AppAnalyticsConfiguration.isUsable(
            dictionary: dictionary,
            bundleIdentifier: bundle.bundleIdentifier
        ),
        let options = FirebaseOptions(contentsOfFile: configurationURL.path) else {
            logger.notice("Firebase Analytics is disabled because its iOS configuration is unavailable.")
            return
        }

        if FirebaseApp.app() == nil {
            FirebaseApp.configure(options: options)
        }

        #if DEBUG
        let appEnvironment = "development"
        #else
        let appEnvironment = "production"
        #endif

        Analytics.setAnalyticsCollectionEnabled(true)
        Analytics.setDefaultEventParameters([
            "app_environment": appEnvironment
        ])
        isConfigured = true
        logger.info("Firebase Analytics collection enabled environment=\(appEnvironment, privacy: .public)")
        #else
        logger.notice("Firebase Analytics SDK is not linked for this target.")
        #endif
    }

    static func setLanguage(_ language: AppLanguage) {
        setUserProperty(language.rawValue, name: "app_language")
    }

    static func setSignedIn(_ isSignedIn: Bool) {
        setUserProperty(isSignedIn ? "signed_in" : "signed_out", name: "auth_state")
    }

    static func screen(_ screen: AppAnalyticsScreen) {
        log(
            "screen_view",
            parameters: [
                "screen_name": screen.rawValue,
                "screen_class": "SwiftUI"
            ]
        )
    }

    static func login(
        method: AppAnalyticsLoginMethod,
        outcome: AppAnalyticsLoginOutcome
    ) {
        log(
            "login_flow",
            parameters: [
                "method": method.rawValue,
                "outcome": outcome.rawValue
            ]
        )
    }

    static func studyCreated(kind: AppAnalyticsStudyKind) {
        log("study_created", parameters: ["study_kind": kind.rawValue])
    }

    static func questionRequested(source: AppAnalyticsQuestionSource) {
        log("question_requested", parameters: ["source": source.rawValue])
    }

    static func questionGenerationCompleted(source: AppAnalyticsQuestionSource) {
        log("question_completed", parameters: ["source": source.rawValue])
    }

    static func questionGenerationFailed(source: AppAnalyticsQuestionSource) {
        log("question_failed", parameters: ["source": source.rawValue])
    }

    static func answerSubmitted() {
        log("answer_submitted")
    }

    static func answerGradingCompleted() {
        log("answer_grading_completed")
    }

    static func answerGradingFailed() {
        log("answer_grading_failed")
    }

    static func notificationOpened(kind: AppAnalyticsNotificationKind) {
        log("notification_opened", parameters: ["notification_kind": kind.rawValue])
    }

    private static func setUserProperty(_ value: String?, name: String) {
        guard isConfigured else {
            return
        }

        #if canImport(FirebaseAnalytics)
        Analytics.setUserProperty(value, forName: name)
        #endif
    }

    private static func log(
        _ name: String,
        parameters: [String: Any]? = nil
    ) {
        guard isConfigured else {
            return
        }

        #if canImport(FirebaseAnalytics)
        Analytics.logEvent(name, parameters: parameters)
        #endif
    }
}
