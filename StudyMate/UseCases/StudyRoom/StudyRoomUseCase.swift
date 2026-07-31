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
        query: String,
        language: AppLanguage
    ) async throws -> BackendStudyPage {
        try await repository.fetchStudy(
            registration: registration,
            limit: limit,
            offset: offset,
            query: query,
            language: language
        )
    }

    func fetchStudyDetail(
        registration: RemotePushRegistration,
        studyID: Int,
        language: AppLanguage
    ) async throws -> BackendStudyRoom {
        try await repository.fetchStudyDetail(
            registration: registration,
            studyID: studyID,
            language: language
        )
    }

    func createStudy(
        registration: RemotePushRegistration,
        category: StudyCategory,
        settings: StudySettings
    ) async throws -> BackendStudyRoom {
        try await repository.createStudy(
            registration: registration,
            category: category,
            settings: settings
        )
    }

    func createStudyTopic(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        topic: String,
        difficulty: Difficulty,
        sortOrder: Int,
        activeForQuestions: Bool = true
    ) async throws -> BackendStudyRoom {
        try await repository.createStudyTopic(
            registration: registration,
            parentStudyID: parentStudyID,
            topic: topic,
            difficulty: difficulty,
            sortOrder: sortOrder,
            activeForQuestions: activeForQuestions
        )
    }

    func suggestStudyTopics(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        count: Int = 10
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
        studyID: Int,
        idempotencyKey: String
    ) async throws -> QuestionGenerationAccepted {
        try await repository.createQuestion(
            registration: registration,
            studyID: studyID,
            idempotencyKey: idempotencyKey
        )
    }

    func fetchQuestionGenerationProcess(
        registration: RemotePushRegistration,
        correlationID: String
    ) async throws -> QuestionGenerationProcess {
        try await repository.fetchQuestionGenerationProcess(
            registration: registration,
            correlationID: correlationID
        )
    }
}
