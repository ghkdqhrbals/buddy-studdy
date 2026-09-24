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

    static func sameQuestionInstance(_ lhs: QuestionItem, _ rhs: QuestionItem) -> Bool {
        lhs.createdAt == rhs.createdAt && questionsMatch(lhs.question, rhs.question)
    }

    static func recordMatchesQuestion(_ record: StudyRecord, question: QuestionItem) -> Bool {
        guard record.isQuestion else { return false }
        if record.isFollowUp || record.isCustomQuestion {
            return sameQuestionInstance(record.question, question)
        }
        return record.question.createdAt == question.createdAt ||
            questionsMatch(record.question.question, question.question)
    }

    static func recordsMatch(_ lhs: StudyRecord, _ rhs: StudyRecord) -> Bool {
        guard lhs.recordType == rhs.recordType else { return false }
        if lhs.isDetachedLocalQuestion || rhs.isDetachedLocalQuestion { return lhs.id == rhs.id }
        if lhs.id == rhs.id { return true }
        // Follow-ups and authored questions keep distinct identities when their wording repeats.
        guard !lhs.isFollowUp, !rhs.isFollowUp, !lhs.isCustomQuestion, !rhs.isCustomQuestion else { return false }
        return lhs.isQuestion && rhs.isQuestion &&
            questionsMatch(lhs.question.question, rhs.question.question)
    }

    static func cachedRecord(matching record: StudyRecord, in records: [StudyRecord]) -> StudyRecord? {
        records.first {
            $0.id == record.id && $0.recordType == record.recordType &&
                ($0.studyID == nil || record.studyID == nil || $0.studyID == record.studyID)
        }
    }
}
