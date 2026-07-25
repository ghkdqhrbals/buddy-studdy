import Foundation

@MainActor
struct RemoteStudyRoomRepository: StudyRoomRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchStudy(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String
    ) async throws -> BackendStudyPage {
        try await backendClient.fetchStudy(
            registration: registration,
            limit: limit,
            offset: offset,
            query: query
        )
    }

    func createStudy(
        registration: RemotePushRegistration,
        category: StudyCategory,
        settings: StudySettings,
        parentStudyID: Int?,
        sortOrder: Int
    ) async throws -> BackendStudyRoom {
        try await backendClient.createStudy(
            registration: registration,
            category: category,
            settings: settings,
            parentStudyID: parentStudyID,
            sortOrder: sortOrder
        )
    }

    func updateStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        category: StudyCategory,
        settings: StudySettings
    ) async throws {
        try await backendClient.updateStudy(
            registration: registration,
            studyID: studyID,
            category: category,
            settings: settings
        )
    }

    func deleteStudy(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws {
        try await backendClient.deleteStudy(registration: registration, studyID: studyID)
    }

    func fetchQuestionQuota(
        registration: RemotePushRegistration
    ) async throws -> BackendQuestionQuota {
        try await backendClient.fetchQuestionQuota(registration: registration)
    }

    func createQuestion(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws -> StudyRecord {
        try await backendClient.createQuestion(registration: registration, studyID: studyID)
    }
}
