import XCTest
@testable import StudyMate

final class QuestionSchedulePolicyTests: XCTestCase {
    func testRemotePushRequiresOnlyAnEnabledScheduleAndAPNSToken() {
        XCTAssertTrue(
            QuestionSchedulePolicy.shouldEnableRemotePush(
                isRunning: true,
                apnsToken: "device-token"
            )
        )
    }

    func testRemotePushIsDisabledWithoutAPNSToken() {
        XCTAssertFalse(
            QuestionSchedulePolicy.shouldEnableRemotePush(
                isRunning: true,
                apnsToken: "  "
            )
        )
    }

    func testRemotePushIsDisabledWhenScheduleIsStopped() {
        XCTAssertFalse(
            QuestionSchedulePolicy.shouldEnableRemotePush(
                isRunning: false,
                apnsToken: "device-token"
            )
        )
    }

    func testNextDueDateUsesLatestQuestionDateAcrossCurrentQuestionAndRecords() {
        let now = Date(timeIntervalSince1970: 1_800)
        let currentQuestion = QuestionItem(
            question: "Current",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 1_000)
        )
        let records = [
            makeRecord(createdAt: Date(timeIntervalSince1970: 900)),
            makeRecord(createdAt: Date(timeIntervalSince1970: 1_200))
        ]

        let nextDueAt = QuestionSchedulePolicy.nextDueDate(
            now: now,
            intervalMinutes: 15,
            currentQuestion: currentQuestion,
            studyRecords: records
        )

        XCTAssertEqual(nextDueAt, Date(timeIntervalSince1970: 2_100))
    }

    func testNextDueDateFallsBackToNowPlusIntervalWhenThereIsNoQuestionHistory() {
        let now = Date(timeIntervalSince1970: 10_000)

        let nextDueAt = QuestionSchedulePolicy.nextDueDate(
            now: now,
            intervalMinutes: 30,
            currentQuestion: nil,
            studyRecords: []
        )

        XCTAssertEqual(nextDueAt, Date(timeIntervalSince1970: 11_800))
    }

    func testDueWhenLatestQuestionPlusIntervalIsNotAfterNow() {
        let now = Date(timeIntervalSince1970: 5_000)
        let currentQuestion = QuestionItem(
            question: "Due",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 4_100)
        )

        XCTAssertTrue(
            QuestionSchedulePolicy.isDue(
                now: now,
                intervalMinutes: 15,
                currentQuestion: currentQuestion,
                studyRecords: []
            )
        )
    }

    private func makeRecord(createdAt: Date) -> StudyRecord {
        StudyRecord(
            question: QuestionItem(
                question: "Record",
                expectedAnswerHint: nil,
                createdAt: createdAt
            ),
            topic: "Architecture",
            difficulty: Difficulty(level: 5)
        )
    }
}
