import XCTest
@testable import StudyMate

final class StudyReviewPromptTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private func day(_ value: Int) -> Date { start.addingTimeInterval(Double(value) * 86_400) }

    private func engagedProgress() -> StudyReviewProgress {
        var progress = StudyReviewProgress()
        for offset in [0, 2, 7] { progress.recordCompletion(at: day(offset)) }
        return progress
    }

    func testRequiresThreeLearningDaysAndSevenElapsedDays() {
        var progress = StudyReviewProgress()
        progress.recordCompletion(at: start)
        progress.recordCompletion(at: start.addingTimeInterval(60))
        XCTAssertEqual(progress.learningDays.count, 1)
        progress.recordCompletion(at: day(1))
        XCTAssertFalse(progress.canRequest(at: day(8), version: "1"))
        progress.recordCompletion(at: day(2))
        XCTAssertFalse(progress.canRequest(at: day(6), version: "1"))
        XCTAssertTrue(progress.canRequest(at: day(7), version: "1"))
        XCTAssertFalse(progress.canRequest(at: day(7), version: ""))
    }

    func testVersionCooldownAndRollingAnnualAttemptLimit() {
        var progress = engagedProgress()
        progress.recordRequest(at: day(7), version: "1")
        XCTAssertFalse(progress.canRequest(at: day(127), version: "1"))
        XCTAssertFalse(progress.canRequest(at: day(126), version: "2"))
        XCTAssertTrue(progress.canRequest(at: day(127), version: "2"))
        progress.recordRequest(at: day(127), version: "2")
        progress.recordRequest(at: day(247), version: "3")
        XCTAssertFalse(progress.canRequest(at: day(367), version: "4"))
        XCTAssertTrue(progress.canRequest(at: day(372), version: "4"))
        XCTAssertFalse(progress.canRequest(at: day(1), version: "4"))
    }

    func testOnlyNewVisibleGradingTransitionCountsNotOpeningOldResults() {
        let pending = StudyReviewCompletion(recordID: "one", isGraded: false)
        let graded = StudyReviewCompletion(recordID: "one", isGraded: true)
        XCTAssertTrue(graded.isNewCompletion(after: pending))
        XCTAssertFalse(graded.isNewCompletion(after: graded))
        XCTAssertFalse(graded.isNewCompletion(after: .init(recordID: nil, isGraded: false)))
        XCTAssertFalse(graded.isNewCompletion(after: .init(recordID: "two", isGraded: false)))
        XCTAssertFalse(pending.isNewCompletion(after: graded))
    }

    @MainActor
    func testPersistenceAndConsumptionRequireFreshCompletionAndAreExactlyOnce() throws {
        let suite = "StudyReviewPromptTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
        store.saveStudyReviewProgress(engagedProgress())
        let restoredStore = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
        XCTAssertEqual(restoredStore.loadStudyReviewProgress(), engagedProgress())
        let coordinator = StudyReviewCoordinator(store: restoredStore)
        XCTAssertFalse(coordinator.consumeRequest(at: day(8), version: "1"))
        coordinator.recordLearningCompletion(at: day(8))
        coordinator.cancelPendingRequest()
        XCTAssertFalse(coordinator.consumeRequest(at: day(8), version: "1"))
        coordinator.recordLearningCompletion(at: day(8))
        XCTAssertFalse(coordinator.consumeRequest(at: day(9), version: "1"))
        coordinator.recordLearningCompletion(at: day(9))
        XCTAssertTrue(coordinator.consumeRequest(at: day(9), version: "1"))
        XCTAssertFalse(coordinator.consumeRequest(at: day(9), version: "1"))
        XCTAssertEqual(store.loadStudyReviewProgress().requestDates.count, 1)
        XCTAssertEqual(store.loadStudyReviewProgress().learningDays.count, 3)
    }
}
