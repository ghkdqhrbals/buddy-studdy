import Foundation

@MainActor
struct CommunityUseCase {
    private let repository: CommunityRepository

    init(repository: CommunityRepository) {
        self.repository = repository
    }

    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        excludeDeviceID: String?,
        language: AppLanguage
    ) async throws -> CommunityQuestionsResponse {
        try await repository.fetchPublicQuestions(
            registration: registration,
            query: query,
            limit: limit,
            offset: offset,
            excludeDeviceID: excludeDeviceID,
            language: language
        )
    }

    func fetchPublicQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestion {
        try await repository.fetchPublicQuestion(
            registration: registration,
            questionID: questionID,
            language: language,
            view: view
        )
    }

    func loginWithGoogle(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult {
        try await repository.loginWithGoogle(
            registration: registration,
            idToken: idToken
        )
    }

    func loginWithApple(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult {
        try await repository.loginWithApple(
            registration: registration,
            idToken: idToken
        )
    }

    func requestEmailVerificationCode(
        registration: RemotePushRegistration,
        email: String
    ) async throws -> EmailVerificationCodeResult {
        try await repository.requestEmailVerificationCode(
            registration: registration,
            email: email
        )
    }

    func loginWithEmail(
        registration: RemotePushRegistration,
        email: String,
        password: String,
        verificationCode: String?
    ) async throws -> CommunityLoginResult {
        try await repository.loginWithEmail(
            registration: registration,
            email: email,
            password: password,
            verificationCode: verificationCode
        )
    }

    func logout(registration: RemotePushRegistration) async throws {
        try await repository.logout(registration: registration)
    }

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile {
        try await repository.fetchMyProfile(registration: registration)
    }

    func fetchAvatarCatalog(registration: RemotePushRegistration) async throws -> AvatarCatalogResponse {
        try await repository.fetchAvatarCatalog(registration: registration)
    }

    func updateProfileAvatar(
        registration: RemotePushRegistration,
        avatarMode: String,
        avatarConfig: [String: String],
        avatarColorSeed: String?
    ) async throws -> CommunityUserProfile {
        try await repository.updateProfileAvatar(
            registration: registration,
            avatarMode: avatarMode,
            avatarConfig: avatarConfig,
            avatarColorSeed: avatarColorSeed
        )
    }

    func updateMyProfile(
        registration: RemotePushRegistration,
        displayName: String?,
        bio: String?,
        avatarSymbolName: String?,
        avatarColorSeed: String?,
        avatarMode: String? = nil,
        avatarConfig: [String: String]? = nil,
        allowPublicQuestions: Bool? = nil
    ) async throws -> CommunityUserProfile {
        try await repository.updateMyProfile(
            registration: registration,
            displayName: displayName,
            bio: bio,
            avatarSymbolName: avatarSymbolName,
            avatarColorSeed: avatarColorSeed,
            avatarMode: avatarMode,
            avatarConfig: avatarConfig,
            allowPublicQuestions: allowPublicQuestions
        )
    }

    func withdrawMyProfile(registration: RemotePushRegistration) async throws -> RemotePushRegistration {
        try await repository.withdrawMyProfile(registration: registration)
    }

    func reportQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws {
        try await repository.reportQuestion(
            registration: registration,
            questionID: questionID,
            reason: reason,
            message: message
        )
    }

    func setUserBlocked(
        registration: RemotePushRegistration,
        userID: Int,
        blocked: Bool
    ) async throws -> CommunityUserBlockState {
        try await repository.setUserBlocked(
            registration: registration,
            userID: userID,
            blocked: blocked
        )
    }

    func submitFeedback(
        registration: RemotePushRegistration,
        content: String
    ) async throws {
        try await repository.submitFeedback(
            registration: registration,
            content: content
        )
    }

    func recordNativeAdvertisementView(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        try await repository.recordNativeAdvertisementView(
            registration: registration,
            selectionID: selectionID
        )
    }

    func suppressNativeAdvertisement(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        try await repository.suppressNativeAdvertisement(
            registration: registration,
            selectionID: selectionID
        )
    }

    func setQuestionLike(
        registration: RemotePushRegistration,
        questionID: String,
        isLiked: Bool
    ) async throws -> CommunityLikeState {
        try await repository.setQuestionLike(
            registration: registration,
            questionID: questionID,
            isLiked: isLiked
        )
    }

    func fetchComments(
        registration: RemotePushRegistration,
        questionID: String,
        limit: Int,
        offset: Int,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityCommentsResponse {
        try await repository.fetchComments(
            registration: registration,
            questionID: questionID,
            limit: limit,
            offset: offset,
            language: language,
            view: view
        )
    }

    func createComment(
        registration: RemotePushRegistration,
        questionID: String,
        body: String,
        sourceLanguage: String
    ) async throws -> CommunityQuestionComment {
        try await repository.createComment(
            registration: registration,
            questionID: questionID,
            body: body,
            sourceLanguage: sourceLanguage
        )
    }

    func deleteComment(
        registration: RemotePushRegistration,
        questionID: String,
        commentID: String
    ) async throws {
        try await repository.deleteComment(
            registration: registration,
            questionID: questionID,
            commentID: commentID
        )
    }
}
