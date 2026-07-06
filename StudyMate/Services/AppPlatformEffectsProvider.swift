import Foundation
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
import UserNotifications
#endif

@MainActor
protocol AppPlatformEffectsProviding {
    func runExpiringBackgroundTask(
        named name: String,
        operation: @MainActor @escaping (_ isExpired: @escaping @Sendable () -> Bool) async -> Int
    ) async -> Int

    func setApplicationIconBadge(_ count: Int)
    func open(_ url: URL)
}

@MainActor
struct DefaultAppPlatformEffectsProvider: AppPlatformEffectsProviding {
    func runExpiringBackgroundTask(
        named name: String,
        operation: @MainActor @escaping (_ isExpired: @escaping @Sendable () -> Bool) async -> Int
    ) async -> Int {
        #if os(iOS)
        let expiration = AppBackgroundTaskExpiration()
        let taskIdentifier = UIApplication.shared.beginBackgroundTask(withName: name) {
            expiration.expire()
        }
        defer {
            if taskIdentifier != .invalid {
                UIApplication.shared.endBackgroundTask(taskIdentifier)
            }
        }

        return await operation {
            expiration.isExpired
        }
        #else
        return await operation {
            false
        }
        #endif
    }

    func setApplicationIconBadge(_ count: Int) {
        #if os(iOS)
        Task { @MainActor in
            let badgeCount = max(0, count)
            if #available(iOS 17.0, *) {
                try? await UNUserNotificationCenter.current().setBadgeCount(badgeCount)
            } else {
                UIApplication.shared.applicationIconBadgeNumber = badgeCount
            }
        }
        #endif
    }

    func open(_ url: URL) {
        #if os(macOS)
        NSWorkspace.shared.open(url)
        #elseif os(iOS)
        UIApplication.shared.open(url)
        #endif
    }
}

#if os(iOS)
private final class AppBackgroundTaskExpiration: @unchecked Sendable {
    private let lock = NSLock()
    private var expired = false

    var isExpired: Bool {
        lock.lock()
        defer {
            lock.unlock()
        }

        return expired
    }

    func expire() {
        lock.lock()
        expired = true
        lock.unlock()
    }
}
#endif
