import XCTest
@testable import StudyMate

final class ArchitecturePolicyTests: XCTestCase {
    func testViewModelsDoNotReadBackendErrorPresentationExtensionsDirectly() throws {
        let root = try repositoryRoot()
        let viewModels = root.appendingPathComponent("StudyMate/ViewModels", isDirectory: true)
        let forbiddenPatterns = [
            "RemotePushBackendError",
            "backendError.requiresLogin",
            "backendError.isPageAccessDenied",
            "backendError.requiresEmailVerification",
            "backendError.shouldShowPopup",
            "backendError.shouldShowInlineError",
            "backendError.userFacingMessage(",
            "backendError.presentation(",
        ]

        let violations = try swiftFiles(in: viewModels).flatMap { file -> [String] in
            let content = try String(contentsOf: file, encoding: .utf8)
            return forbiddenPatterns
                .filter { content.contains($0) }
                .map { "\(file.lastPathComponent): \($0)" }
        }

        XCTAssertTrue(violations.isEmpty, "ViewModels must use AppErrorHandlingPolicy instead of RemotePushBackendError UI extensions: \(violations)")
    }

    func testRemotePushBackendErrorDoesNotExposeUIPresentationExtensions() throws {
        let root = try repositoryRoot()
        let policyFile = root.appendingPathComponent("StudyMate/Core/ErrorHandling/BackendErrorPresentationPolicy.swift")
        let content = try String(contentsOf: policyFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("extension RemotePushBackendError"),
            "Backend error UI decisions must stay behind AppErrorHandlingPolicy/BackendErrorPresentationPolicy, not RemotePushBackendError extensions."
        )
    }

    func testAppStateDoesNotCallBackendIdentityTransportDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "remotePushBackendClient.bootstrapAccessToken",
            "remotePushBackendClient.registerDevice",
            "remotePushBackendClient.updatePushToken",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use BackendIdentityUseCase for backend identity transport calls: \(violations)"
        )
    }

    func testAppStateDoesNotOwnBackendTransportComposition() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "private var remotePushBackendClient",
            "usesConfigurableRemotePushBackendClient",
            "makeRemotePushBackendClient(",
            "AppUseCases(backendClient:",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must delegate backend client composition to an app-use-case provider: \(violations)"
        )
    }

    func testAppStateDoesNotInstantiateOAuthServicesDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "GoogleOAuthService(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use an auth use case instead of constructing OAuth services directly: \(violations)"
        )
    }

    func testCommunityUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Community/CommunityUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "CommunityUseCase must depend on CommunityRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("CommunityRepository"),
            "CommunityUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testStudyRoomUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/StudyRoom/StudyRoomUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "StudyRoomUseCase must depend on StudyRoomRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("StudyRoomRepository"),
            "StudyRoomUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testRecordsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Records/RecordsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "RecordsUseCase must depend on RecordsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("RecordsRepository"),
            "RecordsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testStatsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Stats/StatsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "StatsUseCase must depend on StatsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("StatsRepository"),
            "StatsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testNotificationsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Notifications/NotificationsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "NotificationsUseCase must depend on NotificationsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("NotificationsRepository"),
            "NotificationsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testSettingsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Settings/SettingsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "SettingsUseCase must depend on SettingsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("SettingsRepository"),
            "SettingsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testBackendIdentityUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Identity/BackendIdentityUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "BackendIdentityUseCase must depend on IdentityRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("IdentityRepository"),
            "BackendIdentityUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testPageAccessUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/PageAccess/RefreshPageAccessUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "RefreshPageAccessUseCase must depend on PageAccessRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("PageAccessRepository"),
            "RefreshPageAccessUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testAppStateDoesNotAssignRawLocalizedDescriptionToPrimaryErrorMessage() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("errorMessage = error.localizedDescription"),
            "AppState must route raw errors through AppErrorHandlingPolicy before writing user-visible errorMessage."
        )
    }

    func testAuthRangeNumericBackendErrorsRequireLoginWithoutPopup() {
        let apiError = BackendAPIError(
            code: "101",
            numericCode: 101,
            message: "다시 로그인해 주세요.",
            status: 401
        )
        let error = RemotePushBackendError.httpStatus(401, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertNil(resolution.featureMessage)
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertTrue(resolution.requiresLogin)
        XCTAssertTrue(resolution.isPageAccessDenied)
        XCTAssertTrue(resolution.shouldResetBackendIdentity)
        XCTAssertTrue(resolution.shouldClearFeatureMessage)
    }

    func testAuthRangeNumericBackendErrorsRequireLoginWithoutPopupEvenWhenStatusIsForbidden() {
        let apiError = BackendAPIError(
            code: "101",
            numericCode: 101,
            message: "다시 로그인해 주세요.",
            status: 403
        )
        let error = RemotePushBackendError.httpStatus(403, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertNil(resolution.featureMessage)
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertTrue(resolution.requiresLogin)
        XCTAssertTrue(resolution.isPageAccessDenied)
        XCTAssertTrue(resolution.shouldResetBackendIdentity)
        XCTAssertTrue(resolution.shouldClearFeatureMessage)
    }

    func testEmptyBackendStudyPageDecodesAsEmptyList() throws {
        let data = #"{}"#.data(using: .utf8)!

        let page = try JSONDecoder().decode(BackendStudyPage.self, from: data)

        XCTAssertEqual(page.studies, [])
        XCTAssertEqual(page.totalCount, 0)
        XCTAssertEqual(page.limit, 0)
        XCTAssertEqual(page.offset, 0)
    }

    func testValidationBackendErrorsUseServerMessageInline() {
        let apiError = BackendAPIError(
            code: "RECORD_NOT_FOUND",
            message: "기록을 찾을 수 없습니다.",
            status: 404
        )
        let error = RemotePushBackendError.httpStatus(404, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertEqual(resolution.featureMessage, "기록을 찾을 수 없습니다.")
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.isPageAccessDenied)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertFalse(resolution.shouldClearFeatureMessage)
    }

    func testEmailVerificationRequirementStaysInVerificationFlow() {
        let apiError = BackendAPIError(
            code: "AUTH_GOOGLE_REQUIRED",
            message: "Verification code is required.",
            status: 403
        )
        let error = RemotePushBackendError.httpStatus(403, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertEqual(resolution.featureMessage, "Verification code is required.")
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.isPageAccessDenied)
        XCTAssertTrue(resolution.requiresEmailVerification)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertFalse(resolution.shouldClearFeatureMessage)
    }

    func testCancellationClearsFeatureMessageWithoutUserFacingNoise() {
        let resolution = AppErrorHandlingPolicy.resolve(CancellationError(), fallback: "fallback")

        XCTAssertNil(resolution.featureMessage)
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertTrue(resolution.shouldClearFeatureMessage)
    }

    func testDecodingErrorsUseFriendlyInlineMessageWithoutPopup() {
        let context = DecodingError.Context(
            codingPath: [],
            debugDescription: "No value associated with key CodingKeys(stringValue: \"questions\", intValue: nil)."
        )
        let error = DecodingError.keyNotFound(TestCodingKey(stringValue: "questions"), context)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertEqual(resolution.featureMessage, "응답 데이터를 읽을 수 없습니다. 잠시 후 다시 시도하세요.")
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.isPageAccessDenied)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertFalse(resolution.shouldClearFeatureMessage)
    }

    private func repositoryRoot() throws -> URL {
        var current = URL(fileURLWithPath: #filePath)
        while current.path != "/" {
            let project = current.appendingPathComponent("StudyMate.xcodeproj")
            if FileManager.default.fileExists(atPath: project.path) {
                return current
            }
            current.deleteLastPathComponent()
        }

        throw XCTSkip("Repository root could not be resolved from \(#filePath)")
    }

    private func swiftFiles(in directory: URL) throws -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        return try enumerator.compactMap { item in
            guard let url = item as? URL, url.pathExtension == "swift" else {
                return nil
            }

            let values = try url.resourceValues(forKeys: [.isRegularFileKey])
            return values.isRegularFile == true ? url : nil
        }
    }
}

private struct TestCodingKey: CodingKey {
    var stringValue: String
    var intValue: Int?

    init(stringValue: String) {
        self.stringValue = stringValue
    }

    init(intValue: Int) {
        stringValue = "\(intValue)"
        self.intValue = intValue
    }
}
