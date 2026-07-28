import Combine
import Foundation

@MainActor
protocol AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable
    func observeBackendUnauthorized(_ handler: @MainActor @escaping () -> Void) -> AnyCancellable
    func observeBackendMaintenance(
        _ handler: @MainActor @escaping (BackendServiceAvailability) -> Void
    ) -> AnyCancellable
}

@MainActor
struct DefaultAppNotificationEventProvider: AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable {
        NotificationCenter.default.publisher(
            for: APITrafficNotification.didReceiveLog,
            object: nil
        )
        .compactMap { notification -> APITrafficLogEntry? in
            notification.userInfo?[APITrafficNotification.userInfoKey] as? APITrafficLogEntry
        }
        .sink { entry in
            Task { @MainActor in
                handler(entry)
            }
        }
    }

    func observeBackendUnauthorized(_ handler: @MainActor @escaping () -> Void) -> AnyCancellable {
        NotificationCenter.default.publisher(
            for: BackendAuthorizationNotification.didReceiveUnauthorized,
            object: nil
        )
        .sink { _ in
            Task { @MainActor in
                handler()
            }
        }
    }

    func observeBackendMaintenance(
        _ handler: @MainActor @escaping (BackendServiceAvailability) -> Void
    ) -> AnyCancellable {
        NotificationCenter.default.publisher(
            for: BackendServiceAvailabilityNotification.didEnterMaintenance,
            object: nil
        )
        .compactMap { notification in
            notification.userInfo?[BackendServiceAvailabilityNotification.userInfoKey]
                as? BackendServiceAvailability
        }
        .sink { availability in
            Task { @MainActor in
                handler(availability)
            }
        }
    }
}
