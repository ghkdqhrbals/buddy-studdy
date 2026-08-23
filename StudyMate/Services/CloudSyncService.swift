import CloudKit
import Foundation
#if os(macOS)
import Security
#endif

@MainActor
protocol CloudSyncServiceProtocol {
    func fetchState() async throws -> CloudSyncState?
    func saveState(_ state: CloudSyncState) async throws
    func ensureQuestionPushSubscription(language: AppLanguage, sound: NotificationSoundOption) async throws
    func saveQuestionPush(question: QuestionItem, settings: StudySettings) async throws
    func fetchQuestionPush(recordName: String) async throws -> CloudQuestionPush?
}

@MainActor
final class CloudSyncService: CloudSyncServiceProtocol {
    nonisolated static let defaultContainerIdentifier = "iCloud.io.github.ghkdqhrbals.StudyMate"
    nonisolated static let stateRecordType = "StudyMateState"
    nonisolated static let questionPushRecordType = "StudyMateQuestionPush"
    nonisolated static let questionPushSubscriptionID = "studymate-question-push-v1"

    private let container: CKContainer
    private let database: CKDatabase
    private let recordID = CKRecord.ID(recordName: "private-study-state")
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(containerIdentifier: String = CloudSyncService.defaultContainerIdentifier) {
        container = CKContainer(identifier: containerIdentifier)
        database = container.privateCloudDatabase
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    nonisolated static func canUseCloudKitContainer(identifier: String = defaultContainerIdentifier) -> Bool {
        #if os(macOS)
        guard let task = SecTaskCreateFromSelf(nil),
              let entitlementValue = SecTaskCopyValueForEntitlement(
                task,
                "com.apple.developer.icloud-container-identifiers" as CFString,
                nil
              ) else {
            return false
        }

        guard let containerIdentifiers = entitlementValue as? [String] else {
            return false
        }

        return containerIdentifiers.contains(identifier)
        #else
        return true
        #endif
    }

    func fetchState() async throws -> CloudSyncState? {
        do {
            let record = try await database.record(for: recordID)
            return try state(from: record)
        } catch let error as CKError where error.code == .unknownItem {
            return nil
        }
    }

    func saveState(_ state: CloudSyncState) async throws {
        let record: CKRecord

        do {
            record = try await database.record(for: recordID)
        } catch let error as CKError where error.code == .unknownItem {
            record = CKRecord(recordType: Self.stateRecordType, recordID: recordID)
        }

        let data = try encoder.encode(state)
        record["updatedAt"] = state.updatedAt as NSDate
        record["schemaVersion"] = state.schemaVersion as NSNumber
        record["payload"] = try makeAsset(data: data)

        _ = try await database.save(record)
    }

    func ensureQuestionPushSubscription(language: AppLanguage, sound: NotificationSoundOption) async throws {
        let subscription: CKQuerySubscription

        do {
            if let existingSubscription = try await database.subscription(
                for: Self.questionPushSubscriptionID
            ) as? CKQuerySubscription {
                subscription = existingSubscription
            } else {
                subscription = Self.makeQuestionPushSubscription()
            }
        } catch let error as CKError where error.code == .unknownItem {
            subscription = Self.makeQuestionPushSubscription()
        }

        subscription.notificationInfo = Self.makeQuestionPushNotificationInfo(
            language: language,
            sound: sound
        )

        _ = try await database.save(subscription)
    }

    func saveQuestionPush(question: QuestionItem, settings: StudySettings) async throws {
        let recordName = Self.questionPushRecordName(for: question)
        let recordID = CKRecord.ID(recordName: recordName)

        do {
            _ = try await database.record(for: recordID)
            return
        } catch let error as CKError where error.code == .unknownItem {
            let record = CKRecord(recordType: Self.questionPushRecordType, recordID: recordID)
            record["createdAt"] = question.createdAt as NSDate
            record["question"] = question.question as NSString
            record["topic"] = settings.topic as NSString
            record["difficultyLevel"] = settings.difficulty.level as NSNumber
            record["language"] = settings.appLanguage.rawValue as NSString
            if let hint = question.expectedAnswerHint {
                record["expectedAnswerHint"] = hint as NSString
            }

            _ = try await database.save(record)
        }
    }

    func fetchQuestionPush(recordName: String) async throws -> CloudQuestionPush? {
        let record = try await database.record(for: CKRecord.ID(recordName: recordName))
        return Self.questionPush(from: record)
    }

    nonisolated static func questionPushRecordName(for question: QuestionItem) -> String {
        let milliseconds = Int64((question.createdAt.timeIntervalSince1970 * 1000).rounded())
        return "question-\(milliseconds)"
    }

    private func state(from record: CKRecord) throws -> CloudSyncState? {
        if let asset = record["payload"] as? CKAsset,
           let fileURL = asset.fileURL {
            let data = try Data(contentsOf: fileURL)
            return try decoder.decode(CloudSyncState.self, from: data)
        }

        if let data = record["payloadData"] as? Data {
            return try decoder.decode(CloudSyncState.self, from: data)
        }

        return nil
    }

    private func makeAsset(data: Data) throws -> CKAsset {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("StudyMateCloudSync", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let fileURL = directory.appendingPathComponent("state-\(UUID().uuidString).json")
        try data.write(to: fileURL, options: .atomic)
        return CKAsset(fileURL: fileURL)
    }

    private static func makeQuestionPushSubscription() -> CKQuerySubscription {
        CKQuerySubscription(
            recordType: questionPushRecordType,
            predicate: NSPredicate(format: "TRUEPREDICATE"),
            subscriptionID: questionPushSubscriptionID,
            options: [.firesOnRecordCreation]
        )
    }

    private static func makeQuestionPushNotificationInfo(
        language: AppLanguage,
        sound: NotificationSoundOption
    ) -> CKSubscription.NotificationInfo {
        let strings = AppStrings(language: language)
        let info = CKSubscription.NotificationInfo()
        info.title = strings.newQuestionNotificationTitle
        info.alertBody = strings.cloudQuestionPushBody
        info.category = StudyNotificationAction.category
        info.shouldSendContentAvailable = true
        info.desiredKeys = [
            "createdAt",
            "question",
            "topic",
            "difficultyLevel"
        ]

        if let soundName = sound.cloudKitSoundName {
            info.soundName = soundName
        }

        return info
    }

    private static func questionPush(from record: CKRecord) -> CloudQuestionPush? {
        guard let questionText = record["question"] as? String else {
            return nil
        }

        let createdAt = (record["createdAt"] as? Date) ?? record.creationDate ?? Date()
        let topic = (record["topic"] as? String) ?? ""
        let difficultyLevel = (record["difficultyLevel"] as? NSNumber)?.intValue ?? Difficulty.level5.level
        let hint = record["expectedAnswerHint"] as? String

        return CloudQuestionPush(
            question: QuestionItem(
                question: questionText,
                expectedAnswerHint: hint,
                createdAt: createdAt
            ),
            topic: topic,
            difficulty: Difficulty(level: difficultyLevel)
        )
    }
}

struct CloudQuestionPush: Equatable {
    var question: QuestionItem
    var topic: String
    var difficulty: Difficulty
}

private extension NotificationSoundOption {
    var cloudKitSoundName: String? {
        switch self {
        case .defaultSound:
            "default"
        case .none:
            nil
        case .softPing, .chime, .pop, .bell, .tap:
            bundledFileName ?? "default"
        }
    }
}

enum CloudSyncFailureKind: Equatable {
    case quotaExceeded
    case notAuthenticated
    case permissionDenied
    case network
    case serviceUnavailable
    case rateLimited
    case limitExceeded
    case conflict
    case unavailable
    case unknown
}

enum CloudSyncErrorClassifier {
    static func kind(for error: Error) -> CloudSyncFailureKind {
        guard let cloudKitError = error as? CKError else {
            return .unknown
        }

        if cloudKitError.code == .partialFailure,
           let firstPartialError = cloudKitError.partialErrorsByItemID?.values.first {
            return kind(for: firstPartialError)
        }

        switch cloudKitError.code {
        case .quotaExceeded:
            return .quotaExceeded
        case .notAuthenticated:
            return .notAuthenticated
        case .permissionFailure, .badContainer, .missingEntitlement:
            return .permissionDenied
        case .networkUnavailable, .networkFailure:
            return .network
        case .serviceUnavailable, .zoneBusy:
            return .serviceUnavailable
        case .requestRateLimited:
            return .rateLimited
        case .limitExceeded, .serverRejectedRequest, .assetFileNotFound, .assetFileModified:
            return .limitExceeded
        case .serverRecordChanged, .constraintViolation:
            return .conflict
        case .managedAccountRestricted, .accountTemporarilyUnavailable:
            return .unavailable
        default:
            return .unknown
        }
    }
}
