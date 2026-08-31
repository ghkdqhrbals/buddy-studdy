import Foundation

enum StudyRecordIdentityPolicy {
    static func normalizedQuestionText(_ question: String) -> String {
        question
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    static func questionsMatch(_ lhs: String, _ rhs: String) -> Bool {
        normalizedQuestionText(lhs) == normalizedQuestionText(rhs)
    }

    static func recordsMatch(_ lhs: StudyRecord, _ rhs: StudyRecord) -> Bool {
        guard lhs.recordType == rhs.recordType else { return false }
        if lhs.isDetachedLocalQuestion || rhs.isDetachedLocalQuestion { return lhs.id == rhs.id }
        return lhs.id == rhs.id || (lhs.isQuestion && rhs.isQuestion &&
            questionsMatch(lhs.question.question, rhs.question.question))
    }

    static func cachedRecord(matching record: StudyRecord, in records: [StudyRecord]) -> StudyRecord? {
        records.first {
            $0.id == record.id && $0.recordType == record.recordType &&
                ($0.studyID == nil || record.studyID == nil || $0.studyID == record.studyID)
        }
    }
}
