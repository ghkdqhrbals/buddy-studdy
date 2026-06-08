import Foundation

final class SettingsStore {
    static let maxLogCount = 1000
    static let maxDeletedStudyRecordMarkerCount = 10_000

    private enum Keys {
        static let settings = "studySettings"
        static let currentQuestion = "currentQuestion"
        static let questionHistory = "questionHistory"
        static let studyRecords = "studyRecords"
        static let gradingResult = "gradingResult"
        static let lastAnswer = "lastAnswer"
        static let isRunning = "isRunning"
        static let hasExplicitRunningPreference = "hasExplicitRunningPreference"
        static let apiKey = "openAIAPIKey"
        static let apiKeyUpdatedAt = "openAIAPIKeyUpdatedAt"
        static let localSettingsMutationAt = "localSettingsMutationAt"
        static let questionResponseID = "questionResponseID"
        static let appLogs = "appLogs"
        static let isDebuggingEnabled = "isDebuggingEnabled"
        static let hasCompletedOnboarding = "hasCompletedOnboarding"
        static let isCloudSyncEnabled = "isCloudSyncEnabled"
        static let isCommunitySignedIn = "isCommunitySignedIn"
        static let profileAvatarSymbolName = "profileAvatarSymbolName"
        static let profileAvatarImageData = "profileAvatarImageData"
        static let profileAvatarColorSeed = "profileAvatarColorSeed"
        static let communityProfileDisplayName = "communityProfileDisplayName"
        static let communityProfileID = "communityProfileID"
        static let cloudSyncSnapshotUpdatedAt = "cloudSyncSnapshotUpdatedAt"
        static let deletedStudyRecordMarkers = "deletedStudyRecordMarkers"
        static let studyRecordsClearedAt = "studyRecordsClearedAt"
        static let remotePushRegistration = "remotePushRegistration"
    }

    private let defaults: UserDefaults
    private let recordStore: StudyRecordStorage
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(defaults: UserDefaults = .standard, recordDatabaseURL: URL? = nil) {
        self.defaults = defaults
        Self.removeLegacyRecordDatabaseIfNeeded(defaults: defaults, databaseURL: recordDatabaseURL)
        self.recordStore = Self.makeRecordStore(defaults: defaults, databaseURL: recordDatabaseURL)
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
        migrateLegacyStudyRecordsIfNeeded()
    }

    func loadSettings() -> StudySettings {
        guard let data = defaults.data(forKey: Keys.settings),
              let settings = try? decoder.decode(StudySettings.self, from: data) else {
            return .default
        }

        return StudySettings(
            topic: settings.topic,
            difficulty: settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: settings.studyCategories,
            selectedStudyCategoryID: settings.selectedStudyCategoryID
        )
    }

    func saveSettings(_ settings: StudySettings) {
        let sanitizedSettings = StudySettings(
            topic: settings.topic,
            difficulty: settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: settings.studyCategories,
            selectedStudyCategoryID: settings.selectedStudyCategoryID
        )

        if let data = try? encoder.encode(sanitizedSettings) {
            defaults.set(data, forKey: Keys.settings)
        }
        trimStudyRecords(to: sanitizedSettings.sanitizedMaxHistoryCount)
    }

    func loadQuestion() -> QuestionItem? {
        guard let data = defaults.data(forKey: Keys.currentQuestion) else {
            return nil
        }

        return try? decoder.decode(QuestionItem.self, from: data)
    }

    func saveQuestion(_ question: QuestionItem?) {
        saveOptional(question, forKey: Keys.currentQuestion)
    }

    func loadQuestionHistory() -> [QuestionItem] {
        guard let data = defaults.data(forKey: Keys.questionHistory),
              let questions = try? decoder.decode([QuestionItem].self, from: data) else {
            return []
        }

        return Array(questions.suffix(20))
    }

    func appendQuestionToHistory(_ question: QuestionItem) {
        var questions = loadQuestionHistory()
        let normalizedQuestion = Self.normalizedQuestionText(question.question)

        questions.removeAll {
            Self.normalizedQuestionText($0.question) == normalizedQuestion
        }
        questions.append(question)

        if let data = try? encoder.encode(Array(questions.suffix(20))) {
            defaults.set(data, forKey: Keys.questionHistory)
        }
    }

    func saveQuestionHistory(_ questions: [QuestionItem]) {
        if let data = try? encoder.encode(Array(questions.suffix(20))) {
            defaults.set(data, forKey: Keys.questionHistory)
        }
    }

    func loadStudyRecords() -> [StudyRecord] {
        let deletedMarkers = loadDeletedStudyRecordMarkers()
        let clearedAt = loadStudyRecordsClearedAt()

        return recordStore
            .load(limit: loadSettings().sanitizedMaxHistoryCount)
            .filter {
                !Self.isStudyRecordDeleted($0, markers: deletedMarkers, clearedAt: clearedAt)
            }
    }

    func appendStudyRecord(question: QuestionItem, settings: StudySettings) {
        let record = StudyRecord(
            question: question,
            topic: settings.topic,
            difficulty: settings.difficulty
        )
        recordStore.append(record)
        recordStore.trim(to: loadSettings().sanitizedMaxHistoryCount)
    }

    func updateStudyRecord(question: QuestionItem, answer: String, gradingResult: GradingResult) {
        var record = recordStore.find(question: question) ??
            StudyRecord(
                question: question,
                topic: "",
                difficulty: .beginner
            )
        record.answer = answer
        record.gradingResult = gradingResult
        record.answeredAt = Date()

        recordStore.save(record)
        recordStore.trim(to: loadSettings().sanitizedMaxHistoryCount)
    }

    func updateStudyRecordAnswer(question: QuestionItem, answer: String, onlyIfUngraded: Bool = false) {
        if var record = recordStore.find(question: question) {
            guard !onlyIfUngraded || record.gradingResult == nil else {
                return
            }
            record.answer = answer
            recordStore.save(record)
        } else {
            let record = StudyRecord(
                question: question,
                answer: answer,
                topic: "",
                difficulty: .beginner
            )
            recordStore.append(record)
        }

        recordStore.trim(to: loadSettings().sanitizedMaxHistoryCount)
    }

    func saveStudyRecord(_ record: StudyRecord) {
        recordStore.save(record)
        recordStore.trim(to: loadSettings().sanitizedMaxHistoryCount)
    }

    func deleteStudyRecord(_ record: StudyRecord) {
        markStudyRecordDeleted(record)
        recordStore.delete(record)
    }

    func clearStudyRecords() {
        let recordsToDelete = loadStudyRecords()
        let deletedAt = Date()
        saveStudyRecordsClearedAt(deletedAt)
        saveDeletedStudyRecordMarkers(
            mergedDeletedStudyRecordMarkers(
                loadDeletedStudyRecordMarkers(),
                recordsToDelete.map { DeletedStudyRecordMarker(record: $0, deletedAt: deletedAt) }
            )
        )
        recordStore.clear()
        defaults.removeObject(forKey: Keys.studyRecords)
    }

    func replaceStudyRecords(_ records: [StudyRecord]) {
        let deletedMarkers = loadDeletedStudyRecordMarkers()
        let clearedAt = loadStudyRecordsClearedAt()
        let filteredRecords = records.filter {
            !Self.isStudyRecordDeleted($0, markers: deletedMarkers, clearedAt: clearedAt)
        }
        recordStore.replaceAll(Array(filteredRecords.suffix(loadSettings().sanitizedMaxHistoryCount)))
    }

    func loadDeletedStudyRecordMarkers() -> [DeletedStudyRecordMarker] {
        guard let data = defaults.data(forKey: Keys.deletedStudyRecordMarkers),
              let markers = try? decoder.decode([DeletedStudyRecordMarker].self, from: data) else {
            return []
        }

        return Array(
            markers
                .sorted { $0.deletedAt < $1.deletedAt }
                .suffix(Self.maxDeletedStudyRecordMarkerCount)
        )
    }

    func saveDeletedStudyRecordMarkers(_ markers: [DeletedStudyRecordMarker]) {
        let cappedMarkers = Array(
            markers
                .sorted { $0.deletedAt < $1.deletedAt }
                .suffix(Self.maxDeletedStudyRecordMarkerCount)
        )

        guard !cappedMarkers.isEmpty else {
            defaults.removeObject(forKey: Keys.deletedStudyRecordMarkers)
            return
        }

        if let data = try? encoder.encode(cappedMarkers) {
            defaults.set(data, forKey: Keys.deletedStudyRecordMarkers)
        }
    }

    func markStudyRecordDeleted(_ record: StudyRecord, deletedAt: Date = Date()) {
        saveDeletedStudyRecordMarkers(
            mergedDeletedStudyRecordMarkers(
                loadDeletedStudyRecordMarkers(),
                [DeletedStudyRecordMarker(record: record, deletedAt: deletedAt)]
            )
        )
    }

    func loadStudyRecordsClearedAt() -> Date? {
        guard let value = defaults.object(forKey: Keys.studyRecordsClearedAt) as? TimeInterval else {
            return nil
        }

        return Date(timeIntervalSince1970: value)
    }

    func saveStudyRecordsClearedAt(_ date: Date?) {
        guard let date else {
            defaults.removeObject(forKey: Keys.studyRecordsClearedAt)
            return
        }

        defaults.set(date.timeIntervalSince1970, forKey: Keys.studyRecordsClearedAt)
    }

    func loadQuestionResponseID() -> String? {
        guard let id = defaults.string(forKey: Keys.questionResponseID),
              !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }

        return id
    }

    func saveQuestionResponseID(_ responseID: String?) {
        guard let responseID,
              !responseID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            defaults.removeObject(forKey: Keys.questionResponseID)
            return
        }

        defaults.set(responseID, forKey: Keys.questionResponseID)
    }

    nonisolated static func normalizedQuestionText(_ question: String) -> String {
        question
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private func trimStudyRecords(to limit: Int) {
        recordStore.trim(to: limit)
    }

    func loadGradingResult() -> GradingResult? {
        guard let data = defaults.data(forKey: Keys.gradingResult) else {
            return nil
        }

        return try? decoder.decode(GradingResult.self, from: data)
    }

    func saveGradingResult(_ result: GradingResult?) {
        saveOptional(result, forKey: Keys.gradingResult)
    }

    func loadLastAnswer() -> String {
        defaults.string(forKey: Keys.lastAnswer) ?? ""
    }

    func saveLastAnswer(_ answer: String) {
        defaults.set(answer, forKey: Keys.lastAnswer)
    }

    func loadIsRunning() -> Bool {
        guard defaults.object(forKey: Keys.isRunning) != nil else {
            return true
        }

        return defaults.bool(forKey: Keys.isRunning)
    }

    func saveIsRunning(_ isRunning: Bool) {
        defaults.set(isRunning, forKey: Keys.isRunning)
    }

    func saveExplicitIsRunning(_ isRunning: Bool) {
        saveIsRunning(isRunning)
        defaults.set(true, forKey: Keys.hasExplicitRunningPreference)
    }

    func hasExplicitRunningPreference() -> Bool {
        defaults.bool(forKey: Keys.hasExplicitRunningPreference)
    }

    func loadAPIKey() -> String {
        defaults.string(forKey: Keys.apiKey) ?? ""
    }

    func saveAPIKey(_ apiKey: String) {
        let trimmedKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedKey.isEmpty {
            defaults.removeObject(forKey: Keys.apiKey)
        } else {
            defaults.set(trimmedKey, forKey: Keys.apiKey)
        }
    }

    func loadOpenAIAPIKeyUpdatedAt() -> Date? {
        guard let value = defaults.object(forKey: Keys.apiKeyUpdatedAt) as? TimeInterval else {
            return nil
        }

        return Date(timeIntervalSince1970: value)
    }

    func saveOpenAIAPIKeyUpdatedAt(_ date: Date?) {
        guard let date else {
            defaults.removeObject(forKey: Keys.apiKeyUpdatedAt)
            return
        }

        defaults.set(date.timeIntervalSince1970, forKey: Keys.apiKeyUpdatedAt)
    }

    func loadLocalSettingsMutationAt() -> Date? {
        guard let value = defaults.object(forKey: Keys.localSettingsMutationAt) as? TimeInterval else {
            return nil
        }

        return Date(timeIntervalSince1970: value)
    }

    func saveLocalSettingsMutationAt(_ date: Date?) {
        guard let date else {
            defaults.removeObject(forKey: Keys.localSettingsMutationAt)
            return
        }

        defaults.set(date.timeIntervalSince1970, forKey: Keys.localSettingsMutationAt)
    }

    func loadRemotePushRegistration() -> RemotePushRegistration? {
        guard let data = defaults.data(forKey: Keys.remotePushRegistration) else {
            return nil
        }

        return try? decoder.decode(RemotePushRegistration.self, from: data)
    }

    func saveRemotePushRegistration(_ registration: RemotePushRegistration?) {
        guard let registration else {
            defaults.removeObject(forKey: Keys.remotePushRegistration)
            return
        }

        if let data = try? encoder.encode(registration) {
            defaults.set(data, forKey: Keys.remotePushRegistration)
        }
    }

    func loadIsDebuggingEnabled() -> Bool {
        defaults.bool(forKey: Keys.isDebuggingEnabled)
    }

    func saveIsDebuggingEnabled(_ isEnabled: Bool) {
        defaults.set(isEnabled, forKey: Keys.isDebuggingEnabled)
    }

    func loadHasCompletedOnboarding() -> Bool {
        if defaults.object(forKey: Keys.hasCompletedOnboarding) != nil {
            return defaults.bool(forKey: Keys.hasCompletedOnboarding)
        }

        return defaults.object(forKey: Keys.settings) != nil ||
            !loadAPIKey().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            recordStore.count > 0
    }

    func saveHasCompletedOnboarding(_ hasCompleted: Bool) {
        defaults.set(hasCompleted, forKey: Keys.hasCompletedOnboarding)
    }

    func loadIsCloudSyncEnabled() -> Bool {
        defaults.bool(forKey: Keys.isCloudSyncEnabled)
    }

    func saveIsCloudSyncEnabled(_ isEnabled: Bool) {
        defaults.set(isEnabled, forKey: Keys.isCloudSyncEnabled)
    }

    func loadIsCommunitySignedIn() -> Bool {
        defaults.bool(forKey: Keys.isCommunitySignedIn)
    }

    func saveIsCommunitySignedIn(_ isSignedIn: Bool) {
        defaults.set(isSignedIn, forKey: Keys.isCommunitySignedIn)
    }

    func loadProfileAvatarSymbolName() -> String {
        defaults.string(forKey: Keys.profileAvatarSymbolName) ?? "pixel-buddy"
    }

    func saveProfileAvatarSymbolName(_ symbolName: String) {
        defaults.set(symbolName, forKey: Keys.profileAvatarSymbolName)
    }

    func loadProfileAvatarImageData() -> Data? {
        defaults.data(forKey: Keys.profileAvatarImageData)
    }

    func saveProfileAvatarImageData(_ data: Data?) {
        if let data {
            defaults.set(data, forKey: Keys.profileAvatarImageData)
        } else {
            defaults.removeObject(forKey: Keys.profileAvatarImageData)
        }
    }

    func loadProfileAvatarColorSeed() -> String? {
        defaults.string(forKey: Keys.profileAvatarColorSeed)
    }

    func saveProfileAvatarColorSeed(_ seed: String) {
        defaults.set(seed, forKey: Keys.profileAvatarColorSeed)
    }

    func loadCommunityProfileDisplayName() -> String? {
        defaults.string(forKey: Keys.communityProfileDisplayName)
    }

    func saveCommunityProfileDisplayName(_ displayName: String) {
        let normalizedName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalizedName.isEmpty {
            defaults.removeObject(forKey: Keys.communityProfileDisplayName)
        } else {
            defaults.set(normalizedName, forKey: Keys.communityProfileDisplayName)
        }
    }

    func loadCommunityProfileID() -> Int? {
        guard defaults.object(forKey: Keys.communityProfileID) != nil else {
            return nil
        }
        return defaults.integer(forKey: Keys.communityProfileID)
    }

    func saveCommunityProfileID(_ id: Int?) {
        if let id {
            defaults.set(id, forKey: Keys.communityProfileID)
        } else {
            defaults.removeObject(forKey: Keys.communityProfileID)
        }
    }

    func loadCloudSyncSnapshotUpdatedAt() -> Date? {
        guard let value = defaults.object(forKey: Keys.cloudSyncSnapshotUpdatedAt) as? TimeInterval else {
            return nil
        }

        return Date(timeIntervalSince1970: value)
    }

    func saveCloudSyncSnapshotUpdatedAt(_ date: Date?) {
        guard let date else {
            defaults.removeObject(forKey: Keys.cloudSyncSnapshotUpdatedAt)
            return
        }

        defaults.set(date.timeIntervalSince1970, forKey: Keys.cloudSyncSnapshotUpdatedAt)
    }

    func loadAppLogs() -> [AppLogEntry] {
        guard let data = defaults.data(forKey: Keys.appLogs),
              let logs = try? decoder.decode([AppLogEntry].self, from: data) else {
            return []
        }

        let cappedLogs = cappedAppLogs(logs)
        if cappedLogs.count != logs.count {
            saveAppLogs(cappedLogs)
        }

        return cappedLogs
    }

    func loadAppLogs(page: Int, pageSize: Int) -> AppLogPage {
        let logs = loadAppLogs()
        let totalCount = logs.count
        let sanitizedPageSize = max(1, pageSize)
        let pageCount = max(1, (totalCount + sanitizedPageSize - 1) / sanitizedPageSize)
        let boundedPage = min(max(page, 0), pageCount - 1)
        let newestFirstLogs = logs.reversed()
        let entries = Array(
            newestFirstLogs
                .dropFirst(boundedPage * sanitizedPageSize)
                .prefix(sanitizedPageSize)
        )

        return AppLogPage(
            entries: entries,
            totalCount: totalCount,
            page: boundedPage,
            pageSize: sanitizedPageSize
        )
    }

    func appendAppLog(_ entry: AppLogEntry) {
        var logs = loadAppLogs()
        logs.append(entry)
        saveAppLogs(logs)
    }

    func clearAppLogs() {
        defaults.removeObject(forKey: Keys.appLogs)
    }

    private func saveAppLogs(_ logs: [AppLogEntry]) {
        if let data = try? encoder.encode(cappedAppLogs(logs)) {
            defaults.set(data, forKey: Keys.appLogs)
        }
    }

    private func cappedAppLogs(_ logs: [AppLogEntry]) -> [AppLogEntry] {
        Array(logs.suffix(Self.maxLogCount))
    }

    private func migrateLegacyStudyRecordsIfNeeded() {
        guard let data = defaults.data(forKey: Keys.studyRecords),
              let records = try? decoder.decode([StudyRecord].self, from: data),
              !records.isEmpty else {
            return
        }

        if recordStore.count == 0 {
            recordStore.replaceAll(Array(records.suffix(loadSettings().sanitizedMaxHistoryCount)))
        }

        defaults.removeObject(forKey: Keys.studyRecords)
    }

    private static func makeRecordStore(defaults _: UserDefaults, databaseURL _: URL?) -> StudyRecordStorage {
        InMemoryStudyRecordStore()
    }

    private static func removeLegacyRecordDatabaseIfNeeded(defaults: UserDefaults, databaseURL: URL?) {
        let baseURL: URL?
        if let databaseURL {
            baseURL = databaseURL
        } else if defaults === UserDefaults.standard {
            baseURL = legacyStandardRecordDatabaseURL()
        } else {
            baseURL = nil
        }

        guard let baseURL else {
            return
        }

        let urls = [
            baseURL,
            URL(fileURLWithPath: baseURL.path + "-wal"),
            URL(fileURLWithPath: baseURL.path + "-shm")
        ]
        for url in urls {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private static func legacyStandardRecordDatabaseURL() -> URL? {
        guard let supportDirectory = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: false
        ) else {
            return nil
        }

        return supportDirectory
            .appendingPathComponent("StudyMate", isDirectory: true)
            .appendingPathComponent("StudyMate.sqlite")
    }

    private func saveOptional<T: Encodable>(_ value: T?, forKey key: String) {
        guard let value else {
            defaults.removeObject(forKey: key)
            return
        }

        if let data = try? encoder.encode(value) {
            defaults.set(data, forKey: key)
        }
    }

    private static func isStudyRecordDeleted(
        _ record: StudyRecord,
        markers: [DeletedStudyRecordMarker],
        clearedAt: Date?
    ) -> Bool {
        let sortDate = record.answeredAt ?? record.question.createdAt
        if let clearedAt,
           sortDate <= clearedAt {
            return true
        }

        return markers.contains { marker in
            marker.deletedAt >= sortDate && marker.matches(record)
        }
    }

    private func mergedDeletedStudyRecordMarkers(
        _ lhs: [DeletedStudyRecordMarker],
        _ rhs: [DeletedStudyRecordMarker]
    ) -> [DeletedStudyRecordMarker] {
        var markersByKey: [String: DeletedStudyRecordMarker] = [:]

        for marker in lhs + rhs {
            let key = [
                marker.recordID,
                marker.mergeKey,
                marker.normalizedQuestion
            ].joined(separator: "|")

            if let existingMarker = markersByKey[key] {
                markersByKey[key] = marker.deletedAt >= existingMarker.deletedAt ? marker : existingMarker
            } else {
                markersByKey[key] = marker
            }
        }

        return Array(
            markersByKey.values
                .sorted { $0.deletedAt < $1.deletedAt }
                .suffix(Self.maxDeletedStudyRecordMarkerCount)
        )
    }
}

private protocol StudyRecordStorage: AnyObject {
    var count: Int { get }

    func load(limit: Int) -> [StudyRecord]
    func find(question: QuestionItem) -> StudyRecord?
    func append(_ record: StudyRecord)
    func save(_ record: StudyRecord)
    func delete(_ record: StudyRecord)
    func clear()
    func trim(to limit: Int)
    func replaceAll(_ records: [StudyRecord])
}

private final class InMemoryStudyRecordStore: StudyRecordStorage {
    private var records: [StudyRecord] = []

    var count: Int {
        records.count
    }

    func load(limit: Int) -> [StudyRecord] {
        Array(records.suffix(max(0, limit)))
    }

    func find(question: QuestionItem) -> StudyRecord? {
        let normalizedQuestion = SettingsStore.normalizedQuestionText(question.question)
        return records.last {
            $0.question.createdAt == question.createdAt ||
                SettingsStore.normalizedQuestionText($0.question.question) == normalizedQuestion
        }
    }

    func append(_ record: StudyRecord) {
        let normalizedQuestion = SettingsStore.normalizedQuestionText(record.question.question)
        records.removeAll {
            SettingsStore.normalizedQuestionText($0.question.question) == normalizedQuestion
        }
        records.append(record)
    }

    func save(_ record: StudyRecord) {
        if let index = records.lastIndex(where: { $0.id == record.id }) {
            records[index] = record
        } else {
            append(record)
        }
    }

    func delete(_ record: StudyRecord) {
        let normalizedQuestion = SettingsStore.normalizedQuestionText(record.question.question)
        records.removeAll {
            $0.id == record.id ||
                SettingsStore.normalizedQuestionText($0.question.question) == normalizedQuestion
        }
    }

    func clear() {
        records = []
    }

    func trim(to limit: Int) {
        records = Array(records.suffix(max(0, limit)))
    }

    func replaceAll(_ records: [StudyRecord]) {
        self.records = records
    }
}
