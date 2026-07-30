import Foundation
import OSLog

#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseRemoteConfig)
@preconcurrency import FirebaseRemoteConfig
#endif

enum AppControlDistributionChannel: String, Codable, Sendable {
    case appStore = "APP_STORE"
    case testFlight = "TESTFLIGHT"
}

struct AppControlLocalizedContent: Codable, Equatable, Sendable {
    let ko: String
    let en: String
    let ja: String

    func value(for language: AppLanguage) -> String {
        switch language {
        case .korean: ko
        case .english: en
        case .japanese: ja
        }
    }
}

struct AppControlUpdatePolicy: Codable, Equatable, Sendable {
    let enabled: Bool
    let campaignID: Int64?
    let mode: BackendAppUpdateMode?
    let minimumVersion: String?
    let minimumBuild: String?
    let title: AppControlLocalizedContent?
    let message: AppControlLocalizedContent?
    let storeURL: String?

    private enum CodingKeys: String, CodingKey {
        case enabled
        case campaignID = "campaignId"
        case mode
        case minimumVersion
        case minimumBuild
        case title
        case message
        case storeURL = "storeUrl"
    }
}

struct AppControlMaintenancePolicy: Codable, Equatable, Sendable {
    let enabled: Bool
    let maintenanceID: Int64?
    let startsAt: Date?
    let endsAt: Date?
    let title: AppControlLocalizedContent?
    let message: AppControlLocalizedContent?

    private enum CodingKeys: String, CodingKey {
        case enabled
        case maintenanceID = "maintenanceId"
        case startsAt
        case endsAt
        case title
        case message
    }
}

struct AppControlRemotePolicy: Codable, Equatable, Sendable {
    let schemaVersion: Int
    let policyID: String
    let revision: Int64
    let publishedAt: Date
    let validUntil: Date
    let maintenance: AppControlMaintenancePolicy
    let channels: [String: AppControlUpdatePolicy]

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case policyID = "policyId"
        case revision
        case publishedAt
        case validUntil
        case maintenance
        case channels
    }
}

struct AppControlResolution: Equatable, Sendable {
    let policyID: String?
    let policyRevision: Int64?
    let campaignID: Int64?
    let maintenance: BackendServiceAvailability?
    let update: BackendAppUpdateDecision?
    let action: String
    let nextEvaluationAt: Date?

    static let normal = AppControlResolution(
        policyID: nil,
        policyRevision: nil,
        campaignID: nil,
        maintenance: nil,
        update: nil,
        action: "NORMAL",
        nextEvaluationAt: nil
    )
}

enum AppControlPolicyResolver {
    static func resolve(
        policy: AppControlRemotePolicy?,
        language: AppLanguage,
        channel: AppControlDistributionChannel,
        currentVersion: String,
        currentBuild: String,
        dismissedOptionalCampaignID: Int64?,
        now: Date
    ) -> AppControlResolution {
        guard let policy,
              policy.schemaVersion == 1,
              policy.publishedAt <= now.addingTimeInterval(5 * 60),
              policy.validUntil > now else {
            return .normal
        }

        let maintenance = policy.maintenance
        let startsInFuture = maintenance.startsAt.map { $0 > now } ?? false
        let hasEnded = maintenance.endsAt.map { $0 <= now } ?? false
        if maintenance.enabled, !startsInFuture, !hasEnded {
            let availability = BackendServiceAvailability(
                status: .maintenance,
                maintenanceID: maintenance.maintenanceID.map(Int.init),
                title: maintenance.title?.value(for: language),
                message: maintenance.message?.value(for: language),
                startsAt: maintenance.startsAt,
                endsAt: maintenance.endsAt,
                retryAfterSeconds: nil,
                checkedAt: now
            )
            return AppControlResolution(
                policyID: policy.policyID,
                policyRevision: policy.revision,
                campaignID: nil,
                maintenance: availability,
                update: nil,
                action: "MAINTENANCE",
                nextEvaluationAt: maintenance.endsAt
            )
        }

        let updatePolicy = policy.channels[channel.rawValue]
        let updateRequired = updatePolicy.map {
            $0.enabled && AppControlVersion(currentVersion, currentBuild)
                < AppControlVersion($0.minimumVersion ?? "0", $0.minimumBuild ?? "0")
        } ?? false
        if let updatePolicy, updateRequired {
            let shouldPresent = updatePolicy.mode == .force
                || dismissedOptionalCampaignID != updatePolicy.campaignID
            return AppControlResolution(
                policyID: policy.policyID,
                policyRevision: policy.revision,
                campaignID: updatePolicy.campaignID,
                maintenance: nil,
                update: BackendAppUpdateDecision(
                    updateAvailable: true,
                    shouldPresent: shouldPresent,
                    campaignID: updatePolicy.campaignID,
                    mode: updatePolicy.mode,
                    targetVersion: updatePolicy.minimumVersion,
                    targetBuild: updatePolicy.minimumBuild,
                    title: updatePolicy.title?.value(for: language),
                    message: updatePolicy.message?.value(for: language),
                    appStoreURL: updatePolicy.storeURL
                ),
                action: updatePolicy.mode == .force ? "FORCE_UPDATE" : "OPTIONAL_UPDATE",
                nextEvaluationAt: startsInFuture ? maintenance.startsAt : nil
            )
        }

        return AppControlResolution(
            policyID: policy.policyID,
            policyRevision: policy.revision,
            campaignID: updatePolicy?.enabled == true ? updatePolicy?.campaignID : nil,
            maintenance: nil,
            update: nil,
            action: updatePolicy?.enabled == true ? "UP_TO_DATE" : "NORMAL",
            nextEvaluationAt: startsInFuture ? maintenance.startsAt : nil
        )
    }
}

private struct AppControlVersion: Comparable {
    let version: [Int]
    let build: Int64

    init(_ version: String, _ build: String) {
        self.version = version
            .split(whereSeparator: { $0 == "." || $0 == "-" || $0 == "+" })
            .map { Int($0) ?? 0 }
        self.build = Int64(build) ?? 0
    }

    static func < (lhs: AppControlVersion, rhs: AppControlVersion) -> Bool {
        for index in 0..<max(lhs.version.count, rhs.version.count) {
            let left = index < lhs.version.count ? lhs.version[index] : 0
            let right = index < rhs.version.count ? rhs.version[index] : 0
            if left != right { return left < right }
        }
        return lhs.build < rhs.build
    }
}

@MainActor
protocol AppControlProviding: AnyObject, Sendable {
    func fetchAndActivate() async -> AppControlRemotePolicy?
    func startListening(_ handler: @escaping @MainActor (AppControlRemotePolicy) -> Void)
    nonisolated func stopListening()
}

#if canImport(FirebaseRemoteConfig)
private final class AppControlListenerRegistrationHolder: @unchecked Sendable {
    private let lock = NSLock()
    private var registration: ConfigUpdateListenerRegistration?

    var isEmpty: Bool {
        lock.withLock { registration == nil }
    }

    func store(_ newRegistration: ConfigUpdateListenerRegistration) {
        let previous = lock.withLock {
            let previous = registration
            registration = newRegistration
            return previous
        }
        previous?.remove()
    }

    func remove() {
        let current = lock.withLock {
            let current = registration
            registration = nil
            return current
        }
        current?.remove()
    }

    deinit {
        remove()
    }
}
#endif

@MainActor
final class FirebaseAppControlProvider: AppControlProviding {
    private let logger = Logger(
        subsystem: "io.github.ghkdqhrbals.StudyMate",
        category: "app-control"
    )
    private let parameterKey = "ios_app_control_v1"
    #if canImport(FirebaseRemoteConfig)
    nonisolated private let listenerRegistration = AppControlListenerRegistrationHolder()
    #endif

    func fetchAndActivate() async -> AppControlRemotePolicy? {
        #if canImport(FirebaseRemoteConfig)
        guard FirebaseBootstrap.configureIfPossible() else {
            return nil
        }
        let remoteConfig = RemoteConfig.remoteConfig()
        let settings = RemoteConfigSettings()
        // App control is an operational safety channel. Always request the
        // latest policy on launch/foreground; the realtime listener handles
        // changes while the app remains active.
        settings.minimumFetchInterval = 0
        remoteConfig.configSettings = settings
        remoteConfig.setDefaults([parameterKey: Self.disabledPolicyData as NSObject])
        await withCheckedContinuation { continuation in
            remoteConfig.fetchAndActivate { _, error in
                if let error {
                    self.logger.warning("Remote Config fetch failed: \(error.localizedDescription, privacy: .public)")
                }
                continuation.resume()
            }
        }
        return decode(remoteConfig.configValue(forKey: parameterKey).dataValue)
        #else
        return nil
        #endif
    }

    func startListening(_ handler: @escaping @MainActor (AppControlRemotePolicy) -> Void) {
        #if canImport(FirebaseRemoteConfig)
        guard listenerRegistration.isEmpty, FirebaseBootstrap.configureIfPossible() else {
            return
        }
        let remoteConfig = RemoteConfig.remoteConfig()
        let registration = remoteConfig.addOnConfigUpdateListener { [weak self] update, error in
            guard let self else { return }
            if let error {
                Task { @MainActor in
                    self.logger.warning("Remote Config listener failed: \(error.localizedDescription, privacy: .public)")
                }
                return
            }
            guard update?.updatedKeys.contains(self.parameterKey) == true else {
                return
            }
            remoteConfig.activate { _, activationError in
                Task { @MainActor in
                    if let activationError {
                        self.logger.warning("Remote Config activation failed: \(activationError.localizedDescription, privacy: .public)")
                        return
                    }
                    guard let policy = self.decode(
                        remoteConfig.configValue(forKey: self.parameterKey).dataValue
                    ) else {
                        return
                    }
                    handler(policy)
                }
            }
        }
        listenerRegistration.store(registration)
        #endif
    }

    nonisolated func stopListening() {
        #if canImport(FirebaseRemoteConfig)
        listenerRegistration.remove()
        #endif
    }

    private func decode(_ data: Data) -> AppControlRemotePolicy? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let value = try container.decode(String.self)
            let fractionalFormatter = ISO8601DateFormatter().withFractionalSeconds()
            let standardFormatter = ISO8601DateFormatter()
            if let date = fractionalFormatter.date(from: value)
                ?? standardFormatter.date(from: value) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid ISO-8601 date."
            )
        }
        do {
            return try decoder.decode(AppControlRemotePolicy.self, from: data)
        } catch {
            logger.error("Remote Config policy decode failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    private static let disabledPolicyData = Data(
        """
        {"schemaVersion":1,"policyId":"bundled-default","revision":0,"publishedAt":"2026-01-01T00:00:00Z","validUntil":"2099-01-01T00:00:00Z","maintenance":{"enabled":false,"maintenanceId":null,"startsAt":null,"endsAt":null,"title":null,"message":null},"channels":{"APP_STORE":{"enabled":false,"campaignId":null,"mode":null,"minimumVersion":null,"minimumBuild":null,"title":null,"message":null,"storeUrl":null},"TESTFLIGHT":{"enabled":false,"campaignId":null,"mode":null,"minimumVersion":null,"minimumBuild":null,"title":null,"message":null,"storeUrl":null}}}
        """.utf8
    )
}

@MainActor
enum FirebaseBootstrap {
    private static var attempted = false
    private static var configured = false

    static func configureIfPossible(bundle: Bundle = .main) -> Bool {
        #if canImport(FirebaseCore)
        if FirebaseApp.app() != nil {
            configured = true
            return true
        }
        guard !attempted else { return configured }
        attempted = true
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
            return false
        }
        FirebaseApp.configure(options: options)
        configured = true
        return true
        #else
        return false
        #endif
    }
}

private extension ISO8601DateFormatter {
    func withFractionalSeconds() -> ISO8601DateFormatter {
        formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return self
    }
}
