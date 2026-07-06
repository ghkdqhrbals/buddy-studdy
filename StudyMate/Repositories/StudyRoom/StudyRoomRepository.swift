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
        settings: StudySettings
    ) async throws -> BackendStudyRoom

    func deleteStudy(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws

    func createQuestion(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws -> StudyRecord
}
