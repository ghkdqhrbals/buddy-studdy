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

    func fetchRecordsForStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        limit: Int,
        offset: Int,
        language: AppLanguage
    ) async throws -> BackendRecordsPage

    func gradeRecord(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
    ) async throws -> StudyRecord

    func fetchAnswerGradingProcess(
        registration: RemotePushRegistration,
        correlationID: String,
        afterEventID: Int64
    ) async throws -> AnswerGradingProcess

    func saveRecordAnswer(
        registration: RemotePushRegistration,
        recordID: String,
        answer: String,
        sourceLanguage: String
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
        recordID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> StudyRecord
}
