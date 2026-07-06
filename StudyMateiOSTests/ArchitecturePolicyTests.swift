import XCTest
@testable import StudyMate

final class ArchitecturePolicyTests: XCTestCase {
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
}
