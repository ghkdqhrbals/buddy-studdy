import Foundation

@MainActor
struct RemoteCommunityRepository: CommunityRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        excludeDeviceID: String?,
        language: AppLanguage
    ) async throws -> CommunityQuestionsResponse {
        try await backendClient.fetchPublicQuestions(
            registration: registration,
            query: query,
            limit: limit,
            offset: offset,
            excludeDeviceID: excludeDeviceID,
            language: language
        )
    }

    func fetchLikedPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestionsResponse {
        try await backendClient.fetchLikedPublicQuestions(
            registration: registration,
            query: query,
            limit: limit,
            offset: offset,
            language: language,
            view: view
        )
    }

    func fetchPublicQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestion {
        try await backendClient.fetchPublicQuestion(
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
        try await backendClient.loginWithGoogle(
            registration: registration,
            idToken: idToken
        )
    }

    func loginWithApple(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult {
        try await backendClient.loginWithApple(
            registration: registration,
            idToken: idToken
        )
    }

    func requestEmailVerificationCode(
        registration: RemotePushRegistration,
        email: String
    ) async throws -> EmailVerificationCodeResult {
        try await backendClient.requestEmailVerificationCode(
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
        try await backendClient.loginWithEmail(
            registration: registration,
            email: email,
            password: password,
            verificationCode: verificationCode
        )
    }

    func logout(registration: RemotePushRegistration) async throws {
        try await backendClient.logout(registration: registration)
    }

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile {
        try await backendClient.fetchMyProfile(registration: registration)
    }

    func fetchAvatarCatalog(registration: RemotePushRegistration) async throws -> AvatarCatalogResponse {
        try await backendClient.fetchAvatarCatalog(registration: registration)
    }

    func updateProfileAvatar(
        registration: RemotePushRegistration,
        avatarMode: String,
        avatarConfig: [String: String],
        avatarColorSeed: String?
    ) async throws -> CommunityUserProfile {
        try await backendClient.updateProfileAvatar(
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
        avatarMode: String?,
        avatarConfig: [String: String]?,
        allowPublicQuestions: Bool?
    ) async throws -> CommunityUserProfile {
        try await backendClient.updateMyProfile(
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
        try await backendClient.withdrawMyProfile(registration: registration)
    }

    func reportQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws {
        try await backendClient.reportCommunityQuestion(
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
        try await backendClient.setCommunityUserBlocked(
            registration: registration,
            userID: userID,
            blocked: blocked
        )
    }

    func submitFeedback(
        registration: RemotePushRegistration,
        content: String
    ) async throws {
        try await backendClient.submitAppFeedback(
            registration: registration,
            content: content
        )
    }

    func recordNativeAdvertisementView(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        try await backendClient.recordNativeAdvertisementView(
            registration: registration,
            selectionID: selectionID
        )
    }

    func recordNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        try await backendClient.recordNativeAdvertisementImpression(
            registration: registration,
            selectionID: selectionID
        )
    }

    func suppressNativeAdvertisement(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws {
        try await backendClient.suppressNativeAdvertisement(
            registration: registration,
            selectionID: selectionID
        )
    }

    func setQuestionLike(
        registration: RemotePushRegistration,
        questionID: String,
        isLiked: Bool
    ) async throws -> CommunityLikeState {
        try await backendClient.setCommunityQuestionLike(
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
        try await backendClient.fetchCommunityQuestionComments(
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
        try await backendClient.createCommunityQuestionComment(
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
        try await backendClient.deleteCommunityQuestionComment(
            registration: registration,
            questionID: questionID,
            commentID: commentID
        )
    }
}
