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
        lhs.id == rhs.id || questionsMatch(lhs.question.question, rhs.question.question)
    }
}
