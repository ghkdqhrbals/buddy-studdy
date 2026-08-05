import Foundation

struct AppDistributionContext: Equatable {
    let isTestFlight: Bool
    let buildIdentifier: String
    let isDebugBuild: Bool

    init(
        isTestFlight: Bool,
        buildIdentifier: String,
        isDebugBuild: Bool = false
    ) {
        self.isTestFlight = isTestFlight
        self.buildIdentifier = buildIdentifier
        self.isDebugBuild = isDebugBuild
    }

    var allowsHiddenDeveloperUnlock: Bool {
        isDebugBuild || isTestFlight
    }

    var appVersion: String {
        buildIdentifier.split(separator: "(", maxSplits: 1).first.map(String.init) ?? "0"
    }

    var appBuild: String {
        guard let opening = buildIdentifier.firstIndex(of: "("),
              let closing = buildIdentifier.lastIndex(of: ")"),
              opening < closing else {
            return "0"
        }
        return String(buildIdentifier[buildIdentifier.index(after: opening)..<closing])
    }

    var appControlChannel: AppControlDistributionChannel {
        isTestFlight ? .testFlight : .appStore
    }

    static var live: AppDistributionContext {
        let bundle = Bundle.main
        let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? "unknown"
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            ?? "unknown"
        #if DEBUG
        let isDebugBuild = true
        #else
        let isDebugBuild = false
        #endif
        return AppDistributionContext(
            isTestFlight: bundle.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt",
            buildIdentifier: "\(version)(\(build))",
            isDebugBuild: isDebugBuild
        )
    }
}

@MainActor
struct AppRuntimeDependencies {
    let notificationService: NotificationServicing
    let cloudSyncProvider: CloudSyncProviding
    let platformEffectsProvider: AppPlatformEffectsProviding
    let clipboardProvider: ClipboardProviding
    let appNotificationEventProvider: AppNotificationEventProviding
    let appClock: AppClockProviding
    let appIdentifierProvider: AppIdentifierProviding
    let appTimeZoneProvider: AppTimeZoneProviding
    let appSleepProvider: AppSleepProviding
    let appDistributionContext: AppDistributionContext

    static var live: AppRuntimeDependencies {
        AppRuntimeDependencies(
            notificationService: NotificationService(),
            cloudSyncProvider: DefaultCloudSyncProvider(),
            platformEffectsProvider: DefaultAppPlatformEffectsProvider(),
            clipboardProvider: DefaultClipboardProvider(),
            appNotificationEventProvider: DefaultAppNotificationEventProvider(),
            appClock: SystemAppClockProvider(),
            appIdentifierProvider: UUIDAppIdentifierProvider(),
            appTimeZoneProvider: SystemAppTimeZoneProvider(),
            appSleepProvider: TaskAppSleepProvider(),
            appDistributionContext: .live
        )
    }
}
