import Foundation

@MainActor
protocol RecordsRepository {
    func fetchRecords(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String,
        language: AppLanguage
    ) async throws -> BackendRecordsPage

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord

    func gradingEvents(
        registration: RemotePushRegistration,
        recordID: String,
        afterEventID: Int64
    ) -> AsyncThrowingStream<AnswerGradingProgressEvent, Error>

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String
    ) async throws -> StudyRecord

    func skipRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord

    func deleteRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws

    func updateRecordPublicity(
        registration: RemotePushRegistration,
        recordID: String,
        isPublic: Bool
    ) async throws -> StudyRecord

    func clearRecords(registration: RemotePushRegistration) async throws

    func fetchRecord(
        registration: RemotePushRegistration,
        recordID: String
    ) async throws -> StudyRecord
}
