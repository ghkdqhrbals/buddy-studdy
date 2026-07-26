import Foundation

enum QuestionSchedulePolicy {
    static func shouldEnableIOSRemotePush(apnsToken: String) -> Bool {
        !apnsToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func shouldEnableRemotePush(
        isRunning: Bool,
        apnsToken: String
    ) -> Bool {
        isRunning && !apnsToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func isDue(
        now: Date,
        intervalMinutes: Int,
        currentQuestion: QuestionItem?,
        studyRecords: [StudyRecord]
    ) -> Bool {
        nextDueDate(
            now: now,
            intervalMinutes: intervalMinutes,
            currentQuestion: currentQuestion,
            studyRecords: studyRecords
        ) <= now
    }

    static func nextDueDate(
        now: Date,
        intervalMinutes: Int,
        currentQuestion: QuestionItem?,
        studyRecords: [StudyRecord]
    ) -> Date {
        let interval = TimeInterval(max(intervalMinutes, 1) * 60)
        guard let latestQuestionCreatedAt = latestQuestionDate(
            currentQuestion: currentQuestion,
            studyRecords: studyRecords
        ) else {
            return now.addingTimeInterval(interval)
        }

        return latestQuestionCreatedAt.addingTimeInterval(interval)
    }

    static func latestQuestionDate(
        currentQuestion: QuestionItem?,
        studyRecords: [StudyRecord]
    ) -> Date? {
        let recordDates = studyRecords.map(\.question.createdAt)
        return ([currentQuestion?.createdAt].compactMap { $0 } + recordDates).max()
    }
}
