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
