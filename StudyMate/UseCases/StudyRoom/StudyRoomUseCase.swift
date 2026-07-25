import Foundation

@MainActor
struct StudyRoomUseCase {
    private let repository: StudyRoomRepository

    init(repository: StudyRoomRepository) {
        self.repository = repository
    }

    func fetchStudy(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String
    ) async throws -> BackendStudyPage {
        try await repository.fetchStudy(
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
        parentStudyID: Int? = nil,
        sortOrder: Int = 0
    ) async throws -> BackendStudyRoom {
        try await repository.createStudy(
            registration: registration,
            category: category,
            settings: settings,
            parentStudyID: parentStudyID,
            sortOrder: sortOrder
        )
    }

    func suggestStudyTopics(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        count: Int = 4
    ) async throws -> [String] {
        try await repository.suggestStudyTopics(
            registration: registration,
            parentStudyID: parentStudyID,
            count: count
        )
    }

    func updateStudyTopicActivation(
        registration: RemotePushRegistration,
        studyID: Int,
        active: Bool
    ) async throws -> BackendStudyRoom {
        try await repository.updateStudyTopicActivation(
            registration: registration,
            studyID: studyID,
            active: active
        )
    }

    func updateStudy(
        registration: RemotePushRegistration,
        studyID: Int,
        category: StudyCategory,
        settings: StudySettings
    ) async throws {
        try await repository.updateStudy(
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
        try await repository.deleteStudy(registration: registration, studyID: studyID)
    }

    func fetchQuestionQuota(
        registration: RemotePushRegistration
    ) async throws -> BackendQuestionQuota {
        try await repository.fetchQuestionQuota(registration: registration)
    }

    func createQuestion(
        registration: RemotePushRegistration,
        studyID: Int
    ) async throws -> StudyRecord {
        try await repository.createQuestion(registration: registration, studyID: studyID)
    }
}
