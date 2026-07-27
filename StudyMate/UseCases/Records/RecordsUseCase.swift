import Foundation

@MainActor
struct RecordsUseCase {
    private let repository: RecordsRepository

    init(repository: RecordsRepository) {
        self.repository = repository
    }

    func fetchRecords(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String,
        language: AppLanguage
    ) async throws -> BackendRecordsPage {
        try await repository.fetchRecords(
            registration: registration,
            limit: limit,
            offset: offset,
            query: query,
            language: language
        )
    }

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord {
        try await repository.gradeRecord(registration: registration, recordID: recordID, answer: answer)
    }

    func gradingEvents(
        registration: RemotePushRegistration,
        recordID: String,
        afterEventID: Int64
    ) -> AsyncThrowingStream<AnswerGradingProgressEvent, Error> {
        repository.gradingEvents(
            registration: registration,
            recordID: recordID,
            afterEventID: afterEventID
        )
    }

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord {
        try await repository.saveRecordAnswer(registration: registration, recordID: recordID, answer: answer)
    }

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord {
        try await repository.skipRecord(registration: registration, recordID: recordID)
    }

    func deleteRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws {
        try await repository.deleteRecord(registration: registration, recordID: recordID)
    }

    func updateRecordPublicity(
        registration: RemotePushRegistration,
        recordID: String,
        isPublic: Bool
    ) async throws -> StudyRecord {
        try await repository.updateRecordPublicity(
            registration: registration,
            recordID: recordID,
            isPublic: isPublic
        )
    }

    func clearRecords(registration: RemotePushRegistration) async throws {
        try await repository.clearRecords(registration: registration)
    }

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord {
        try await repository.fetchRecord(registration: registration, recordID: recordID)
    }
}
