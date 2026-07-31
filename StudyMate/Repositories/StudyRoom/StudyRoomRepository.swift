import Foundation

@MainActor
protocol StudyRoomRepository {
    func fetchStudy(
        registration: RemotePushRegistration,
        limit: Int,
        offset: Int,
        query: String,
        language: AppLanguage
    ) async throws -> BackendStudyPage

    func fetchStudyDetail(
        registration: RemotePushRegistration,
        studyID: Int,
        language: AppLanguage
    ) async throws -> BackendStudyRoom

    func createStudy(
        registration: RemotePushRegistration,
        category: StudyCategory,
        settings: StudySettings
    ) async throws -> BackendStudyRoom

    func createStudyTopic(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        topic: String,
        difficulty: Difficulty,
        sortOrder: Int,
        activeForQuestions: Bool
    ) async throws -> BackendStudyRoom

    func suggestStudyTopics(
        registration: RemotePushRegistration,
        parentStudyID: Int,
        count: Int
    ) async throws -> [String]

    func updateStudyTopicActivation(
        registration: RemotePushRegistration,
        studyID: Int,
        active: Bool
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
        studyID: Int,
        idempotencyKey: String
    ) async throws -> QuestionGenerationAccepted

    func fetchQuestionGenerationProcess(
        registration: RemotePushRegistration,
        correlationID: String
    ) async throws -> QuestionGenerationProcess
}
