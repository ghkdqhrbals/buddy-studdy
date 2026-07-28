import Foundation

@MainActor
struct RemoteRecordsRepository: RecordsRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchRecords(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String,
        language: AppLanguage
    ) async throws -> BackendRecordsPage {
        try await backendClient.fetchRecords(
            registration: registration,
            limit: limit,
            offset: offset,
            query: query,
            language: language
        )
    }

    func fetchRecordsForStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        limit: Int,
        offset: Int,
        language: AppLanguage
    ) async throws -> BackendRecordsPage {
        try await backendClient.fetchRecordsForStudy(
            registration: registration,
            studyID: studyID,
            limit: limit,
            offset: offset,
            language: language
        )
    }

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord {
        try await backendClient.gradeRecord(
            registration: registration,
            recordID: recordID,
            answer: answer,
            sourceLanguage: sourceLanguage
        )
    }

    func fetchAnswerGradingProcess(
        registration: RemotePushRegistration,
        correlationID: String,
        afterEventID: Int64
    ) async throws -> AnswerGradingProcess {
        try await backendClient.fetchAnswerGradingProcess(
            registration: registration,
            correlationID: correlationID,
            afterEventID: afterEventID
        )
    }

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord {
        try await backendClient.saveRecordAnswer(
            registration: registration,
            recordID: recordID,
            answer: answer,
            sourceLanguage: sourceLanguage
        )
    }

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord {
        try await backendClient.skipRecord(registration: registration, recordID: recordID)
    }

    func deleteRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws {
        try await backendClient.deleteRecord(registration: registration, recordID: recordID)
    }

    func updateRecordPublicity(
        registration: RemotePushRegistration,
        recordID: String,
        isPublic: Bool
    ) async throws -> StudyRecord {
        try await backendClient.updateRecordPublicity(
            registration: registration,
            recordID: recordID,
            isPublic: isPublic
        )
    }

    func clearRecords(registration: RemotePushRegistration) async throws {
        try await backendClient.clearRecords(registration: registration)
    }

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> StudyRecord {
        try await backendClient.fetchRecord(
            registration: registration,
            recordID: recordID,
            language: language,
            view: view
        )
    }
}
