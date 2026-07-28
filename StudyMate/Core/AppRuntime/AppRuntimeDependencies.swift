import Foundation

struct AppDistributionContext: Equatable {
    let isTestFlight: Bool
    let buildIdentifier: String

    static var live: AppDistributionContext {
        let bundle = Bundle.main
        let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? "unknown"
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            ?? "unknown"
        return AppDistributionContext(
            isTestFlight: bundle.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt",
            buildIdentifier: "\(version)(\(build))"
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
