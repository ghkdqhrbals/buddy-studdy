import Foundation

@MainActor
protocol RecordsRepository {
    #if os(iOS)
    func fetchStudyLearningRecords(
        registration: RemotePushRegistration, studyID: Int, scope: StudyLearningRecordScope,
        limit: Int, cursor: String?, language: AppLanguage, view: LocalizedContentView
    ) async throws -> BackendStudyLearningRecordsPage

    func fetchVoiceStudyLearningRecord(
        registration: RemotePushRegistration, recordID: String,
        language: AppLanguage, view: LocalizedContentView
    ) async throws -> BackendVoiceStudyLearningRecord
    #endif

    func createCustomQuestion(registration: RemotePushRegistration, studyID: Int, draft: CustomQuestionDraft) async throws -> StudyRecord

    func createFollowUp(registration: RemotePushRegistration, recordID: String, idempotencyKey: String) async throws -> QuestionGenerationAccepted
    func fetchRecordThread(registration: RemotePushRegistration, recordID: String, language: AppLanguage) async throws -> [StudyRecord]

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

#if os(iOS)
extension RecordsRepository {
    func fetchStudyLearningRecords(
        registration: RemotePushRegistration, studyID: Int, scope: StudyLearningRecordScope,
        limit: Int, cursor: String?, language: AppLanguage, view: LocalizedContentView
    ) async throws -> BackendStudyLearningRecordsPage { throw StudyLearningRecordsError.unavailable }

    func fetchVoiceStudyLearningRecord(
        registration: RemotePushRegistration, recordID: String,
        language: AppLanguage, view: LocalizedContentView
    ) async throws -> BackendVoiceStudyLearningRecord { throw StudyLearningRecordsError.unavailable }
}
#endif
