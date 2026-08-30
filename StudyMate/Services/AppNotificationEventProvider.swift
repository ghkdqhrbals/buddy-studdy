import Combine
import Foundation

@MainActor
protocol AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable
    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable
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

    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable {
        NotificationCenter.default.publisher(
            for: BackendAuthorizationNotification.didReceiveUnauthorized,
            object: nil
        )
        .compactMap { notification -> BackendUnauthorizedRequestIdentity? in
            notification.userInfo?[BackendAuthorizationNotification.requestIdentityUserInfoKey]
                as? BackendUnauthorizedRequestIdentity
        }
        .sink { identity in
            Task { @MainActor in
                handler(identity)
            }
        }
    }
}
