import Foundation

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
            appSleepProvider: TaskAppSleepProvider()
        )
    }
}
