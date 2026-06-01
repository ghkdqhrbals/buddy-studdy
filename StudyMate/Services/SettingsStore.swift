import Foundation
import SQLite3

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
        static let questionResponseID = "questionResponseID"
        static let appLogs = "appLogs"
        static let isDebuggingEnabled = "isDebuggingEnabled"
        static let hasCompletedOnboarding = "hasCompletedOnboarding"
        static let isCloudSyncEnabled = "isCloudSyncEnabled"
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
            maxHistoryCount: settings.sanitizedMaxHistoryCount
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
            maxHistoryCount: settings.sanitizedMaxHistoryCount
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

    private static func makeRecordStore(defaults: UserDefaults, databaseURL: URL?) -> StudyRecordStorage {
        let resolvedURL: URL
        do {
            resolvedURL = try databaseURL ?? defaultRecordDatabaseURL(defaults: defaults)
            return try SQLiteStudyRecordStore(databaseURL: resolvedURL)
        } catch {
            return InMemoryStudyRecordStore()
        }
    }

    private static func defaultRecordDatabaseURL(defaults: UserDefaults) throws -> URL {
        if defaults !== UserDefaults.standard {
            return FileManager.default.temporaryDirectory
                .appendingPathComponent("studymate-records-\(UUID().uuidString).sqlite")
        }

        let supportDirectory = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let appDirectory = supportDirectory.appendingPathComponent("StudyMate", isDirectory: true)
        try FileManager.default.createDirectory(at: appDirectory, withIntermediateDirectories: true)
        return appDirectory.appendingPathComponent("StudyMate.sqlite")
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

private final class SQLiteStudyRecordStore: StudyRecordStorage {
    private let database: OpaquePointer?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private static let transientDestructor = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    var count: Int {
        (try? intValue("SELECT COUNT(*) FROM study_records")) ?? 0
    }

    init(databaseURL: URL) throws {
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601

        var openedDatabase: OpaquePointer?
        let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &openedDatabase, flags, nil) == SQLITE_OK,
              let openedDatabase else {
            throw SQLiteStudyRecordStoreError.openFailed
        }

        database = openedDatabase
        try migrateSchema()
    }

    deinit {
        sqlite3_close(database)
    }

    func load(limit: Int) -> [StudyRecord] {
        let boundedLimit = max(0, limit)
        guard boundedLimit > 0 else {
            return []
        }

        return (try? records(
            sql: """
            SELECT record_json
            FROM (
              SELECT rowid, record_json
              FROM study_records
              ORDER BY rowid DESC
              LIMIT ?
            )
            ORDER BY rowid ASC
            """,
            bindings: [.integer(boundedLimit)]
        )) ?? []
    }

    func find(question: QuestionItem) -> StudyRecord? {
        let normalizedQuestion = SettingsStore.normalizedQuestionText(question.question)
        return try? records(
            sql: """
            SELECT record_json
            FROM study_records
            WHERE question_created_at = ? OR normalized_question = ?
            ORDER BY rowid DESC
            LIMIT 1
            """,
            bindings: [
                .real(question.createdAt.timeIntervalSince1970),
                .text(normalizedQuestion)
            ]
        ).first
    }

    func append(_ record: StudyRecord) {
        do {
            try execute("BEGIN IMMEDIATE")
            try deleteMatching(normalizedQuestion: SettingsStore.normalizedQuestionText(record.question.question))
            try insert(record)
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
        }
    }

    func save(_ record: StudyRecord) {
        do {
            try execute("BEGIN IMMEDIATE")
            if try !update(record) {
                try deleteMatching(normalizedQuestion: SettingsStore.normalizedQuestionText(record.question.question))
                try insert(record)
            }
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
        }
    }

    func delete(_ record: StudyRecord) {
        do {
            try deleteMatching(
                id: record.id,
                normalizedQuestion: SettingsStore.normalizedQuestionText(record.question.question)
            )
        } catch {
            return
        }
    }

    func clear() {
        try? execute("DELETE FROM study_records")
    }

    func trim(to limit: Int) {
        let boundedLimit = max(0, limit)
        do {
            if boundedLimit == 0 {
                try execute("DELETE FROM study_records")
                return
            }

            try run(
                """
                DELETE FROM study_records
                WHERE rowid NOT IN (
                  SELECT rowid
                  FROM study_records
                  ORDER BY rowid DESC
                  LIMIT ?
                )
                """,
                bindings: [.integer(boundedLimit)]
            )
        } catch {
            return
        }
    }

    func replaceAll(_ records: [StudyRecord]) {
        do {
            try execute("BEGIN IMMEDIATE")
            try execute("DELETE FROM study_records")
            for record in records {
                try insert(record)
            }
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
        }
    }

    private func migrateSchema() throws {
        try execute("PRAGMA journal_mode=WAL")
        try execute("PRAGMA foreign_keys=ON")
        try execute(
            """
            CREATE TABLE IF NOT EXISTS study_records (
              id TEXT PRIMARY KEY NOT NULL,
              normalized_question TEXT NOT NULL UNIQUE,
              question_text TEXT NOT NULL,
              expected_answer_hint TEXT,
              question_created_at REAL NOT NULL,
              answer TEXT,
              topic TEXT NOT NULL,
              difficulty INTEGER NOT NULL,
              grading_score INTEGER,
              is_correct INTEGER,
              answered_at REAL,
              record_json BLOB NOT NULL
            )
            """
        )
        try execute("CREATE INDEX IF NOT EXISTS idx_study_records_created_at ON study_records(question_created_at)")
        try execute("CREATE INDEX IF NOT EXISTS idx_study_records_topic ON study_records(topic)")
        try execute("CREATE INDEX IF NOT EXISTS idx_study_records_answered_at ON study_records(answered_at)")
    }

    @discardableResult
    private func update(_ record: StudyRecord) throws -> Bool {
        try run(
            """
            UPDATE study_records
            SET normalized_question = ?,
                question_text = ?,
                expected_answer_hint = ?,
                question_created_at = ?,
                answer = ?,
                topic = ?,
                difficulty = ?,
                grading_score = ?,
                is_correct = ?,
                answered_at = ?,
                record_json = ?
            WHERE id = ?
            """,
            bindings: recordBindings(record) + [.text(record.id)]
        )

        return sqlite3_changes(database) > 0
    }

    private func insert(_ record: StudyRecord) throws {
        try run(
            """
            INSERT INTO study_records (
              id,
              normalized_question,
              question_text,
              expected_answer_hint,
              question_created_at,
              answer,
              topic,
              difficulty,
              grading_score,
              is_correct,
              answered_at,
              record_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            bindings: [.text(record.id)] + recordBindings(record)
        )
    }

    private func deleteMatching(id: String? = nil, normalizedQuestion: String) throws {
        if let id {
            try run(
                "DELETE FROM study_records WHERE id = ? OR normalized_question = ?",
                bindings: [.text(id), .text(normalizedQuestion)]
            )
        } else {
            try run(
                "DELETE FROM study_records WHERE normalized_question = ?",
                bindings: [.text(normalizedQuestion)]
            )
        }
    }

    private func recordBindings(_ record: StudyRecord) throws -> [SQLiteBinding] {
        let recordData = try encoder.encode(record)
        let normalizedQuestion = SettingsStore.normalizedQuestionText(record.question.question)
        let isCorrect = record.gradingResult.map { $0.isCorrect ? 1 : 0 }

        return [
            .text(normalizedQuestion),
            .text(record.question.question),
            .optionalText(record.question.expectedAnswerHint),
            .real(record.question.createdAt.timeIntervalSince1970),
            .optionalText(record.answer),
            .text(record.topic),
            .integer(record.difficulty.level),
            .optionalInteger(record.gradingResult?.score),
            .optionalInteger(isCorrect),
            .optionalReal(record.answeredAt?.timeIntervalSince1970),
            .blob(recordData)
        ]
    }

    private func records(sql: String, bindings: [SQLiteBinding] = []) throws -> [StudyRecord] {
        try prepare(sql, bindings: bindings) { statement in
            var records: [StudyRecord] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                guard let record = try decodeRecord(statement: statement, column: 0) else {
                    continue
                }
                records.append(record)
            }
            return records
        }
    }

    private func decodeRecord(statement: OpaquePointer?, column: Int32) throws -> StudyRecord? {
        guard let bytes = sqlite3_column_blob(statement, column) else {
            return nil
        }

        let count = Int(sqlite3_column_bytes(statement, column))
        let data = Data(bytes: bytes, count: count)
        return try decoder.decode(StudyRecord.self, from: data)
    }

    private func intValue(_ sql: String) throws -> Int {
        try prepare(sql) { statement in
            guard sqlite3_step(statement) == SQLITE_ROW else {
                return 0
            }
            return Int(sqlite3_column_int64(statement, 0))
        }
    }

    private func execute(_ sql: String) throws {
        var errorMessage: UnsafeMutablePointer<Int8>?
        guard sqlite3_exec(database, sql, nil, nil, &errorMessage) == SQLITE_OK else {
            let message: String
            if let errorMessage {
                message = String(cString: errorMessage)
            } else {
                message = String(cString: sqlite3_errmsg(database))
            }
            defer {
                sqlite3_free(errorMessage)
            }
            throw SQLiteStudyRecordStoreError.executionFailed(message)
        }
    }

    private func run(_ sql: String, bindings: [SQLiteBinding] = []) throws {
        try prepare(sql, bindings: bindings) { statement in
            guard sqlite3_step(statement) == SQLITE_DONE else {
                throw SQLiteStudyRecordStoreError.executionFailed(String(cString: sqlite3_errmsg(database)))
            }
        }
    }

    private func prepare<T>(
        _ sql: String,
        bindings: [SQLiteBinding] = [],
        body: (OpaquePointer?) throws -> T
    ) throws -> T {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else {
            throw SQLiteStudyRecordStoreError.executionFailed(String(cString: sqlite3_errmsg(database)))
        }
        defer {
            sqlite3_finalize(statement)
        }

        for (index, binding) in bindings.enumerated() {
            try bind(binding, to: statement, index: Int32(index + 1))
        }

        return try body(statement)
    }

    private func bind(_ binding: SQLiteBinding, to statement: OpaquePointer?, index: Int32) throws {
        let result: Int32
        switch binding {
        case .text(let value):
            result = sqlite3_bind_text(statement, index, value, -1, Self.transientDestructor)
        case .optionalText(let value):
            if let value {
                result = sqlite3_bind_text(statement, index, value, -1, Self.transientDestructor)
            } else {
                result = sqlite3_bind_null(statement, index)
            }
        case .integer(let value):
            result = sqlite3_bind_int64(statement, index, sqlite3_int64(value))
        case .optionalInteger(let value):
            if let value {
                result = sqlite3_bind_int64(statement, index, sqlite3_int64(value))
            } else {
                result = sqlite3_bind_null(statement, index)
            }
        case .real(let value):
            result = sqlite3_bind_double(statement, index, value)
        case .optionalReal(let value):
            if let value {
                result = sqlite3_bind_double(statement, index, value)
            } else {
                result = sqlite3_bind_null(statement, index)
            }
        case .blob(let value):
            result = value.withUnsafeBytes { buffer in
                sqlite3_bind_blob(statement, index, buffer.baseAddress, Int32(value.count), Self.transientDestructor)
            }
        }

        guard result == SQLITE_OK else {
            throw SQLiteStudyRecordStoreError.executionFailed(String(cString: sqlite3_errmsg(database)))
        }
    }
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

private enum SQLiteBinding {
    case text(String)
    case optionalText(String?)
    case integer(Int)
    case optionalInteger(Int?)
    case real(Double)
    case optionalReal(Double?)
    case blob(Data)
}

private enum SQLiteStudyRecordStoreError: Error {
    case openFailed
    case executionFailed(String)
}
