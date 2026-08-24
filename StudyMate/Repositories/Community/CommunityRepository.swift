import Foundation

@MainActor
protocol CommunityRepository {
    func fetchPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        excludeDeviceID: String?,
        language: AppLanguage
    ) async throws -> CommunityQuestionsResponse

    func fetchLikedPublicQuestions(
        registration: RemotePushRegistration,
        query: String?,
        limit: Int,
        offset: Int,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestionsResponse

    func fetchPublicQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityQuestion

    func loginWithGoogle(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult

    func loginWithApple(
        registration: RemotePushRegistration,
        idToken: String
    ) async throws -> CommunityLoginResult

    func requestEmailVerificationCode(
        registration: RemotePushRegistration,
        email: String
    ) async throws -> EmailVerificationCodeResult

    func loginWithEmail(
        registration: RemotePushRegistration,
        email: String,
        password: String,
        verificationCode: String?
    ) async throws -> CommunityLoginResult

    func logout(registration: RemotePushRegistration) async throws

    func fetchMyProfile(registration: RemotePushRegistration) async throws -> CommunityUserProfile

    func fetchAvatarCatalog(registration: RemotePushRegistration) async throws -> AvatarCatalogResponse

    func updateProfileAvatar(
        registration: RemotePushRegistration,
        avatarMode: String,
        avatarConfig: [String: String],
        avatarColorSeed: String?
    ) async throws -> CommunityUserProfile

    func updateMyProfile(
        registration: RemotePushRegistration,
        displayName: String?,
        bio: String?,
        avatarSymbolName: String?,
        avatarColorSeed: String?,
        avatarMode: String?,
        avatarConfig: [String: String]?,
        allowPublicQuestions: Bool?
    ) async throws -> CommunityUserProfile

    func withdrawMyProfile(registration: RemotePushRegistration) async throws -> RemotePushRegistration

    func reportQuestion(
        registration: RemotePushRegistration,
        questionID: String,
        reason: String,
        message: String
    ) async throws

    func setUserBlocked(
        registration: RemotePushRegistration,
        userID: Int,
        blocked: Bool
    ) async throws -> CommunityUserBlockState

    func submitFeedback(
        registration: RemotePushRegistration,
        content: String
    ) async throws

    func recordNativeAdvertisementView(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws

    func recordNativeAdvertisementImpression(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws

    func suppressNativeAdvertisement(
        registration: RemotePushRegistration,
        selectionID: String
    ) async throws

    func setQuestionLike(
        registration: RemotePushRegistration,
        questionID: String,
        isLiked: Bool
    ) async throws -> CommunityLikeState

    func fetchComments(
        registration: RemotePushRegistration,
        questionID: String,
        limit: Int,
        offset: Int,
        language: AppLanguage,
        view: LocalizedContentView
    ) async throws -> CommunityCommentsResponse

    func createComment(
        registration: RemotePushRegistration,
        questionID: String,
        body: String,
        sourceLanguage: String
    ) async throws -> CommunityQuestionComment

    func deleteComment(
        registration: RemotePushRegistration,
        questionID: String,
        commentID: String
    ) async throws
}
