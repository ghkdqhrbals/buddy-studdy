import Foundation

@MainActor
protocol StudyRoomRepository {
    func fetchStudy(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String
    ) async throws -> BackendStudyPage

    func createStudy(
        registration: RemotePushRegistration,
        category: StudyCategory,
        settings: StudySettings,
        parentStudyID: Int?,
        sortOrder: Int
    ) async throws -> BackendStudyRoom

    func updateStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        category: StudyCategory,
        settings: StudySettings
    ) async throws

    func deleteStudy(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws

    func fetchQuestionQuota(
        registration: RemotePushRegistration
    ) async throws -> BackendQuestionQuota

    func createQuestion(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws -> StudyRecord
}
