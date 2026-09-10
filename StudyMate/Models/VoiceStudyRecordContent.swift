import Foundation

enum StudyRecordType: String, Codable, Equatable, Sendable {
    case question = "QUESTION"
    case voiceTutor = "VOICE_TUTOR"

    var symbolName: String { self == .voiceTutor ? "waveform" : "text.bubble" }
}

/// A response/cache identity, never credentials or persistent user data.
struct CommonRecordsIdentity: Hashable, Sendable {
    var userID: Int?
    var sessionGeneration: UInt64
    var backendGeneration: Int
    var languageCode: String
}

/// The shareable content of one voice exchange, not a session or a recording.
/// Session IDs, transcript IDs, and audio access are deliberately not part of
/// the common records/public-comments contract.
struct VoiceRecordContent: Codable, Equatable {
    enum Kind: String, Codable, Equatable {
        case tutorQuestion = "TUTOR_QUESTION"
        case learnerQuestion = "LEARNER_QUESTION"
    }

    var kind: Kind
    var score: Int?
    var feedback: String?
    var strengths: [String]
    var improvements: [String]
    var depthSummary: String
    var sourceLanguage: String
    var requestedLanguage: String
    var displayLanguage: String
    var translationPending: Bool

    func displayScore(answer: String?) -> Int? {
        guard kind == .tutorQuestion,
              answer?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false,
              let score, (0...100).contains(score) else { return nil }
        return score
    }

    var containsTranslation: Bool {
        !sourceLanguage.isEmpty && !displayLanguage.isEmpty &&
            sourceLanguage != displayLanguage
    }
}

extension StudyRecord {
    var isQuestion: Bool { recordType == .question }
    var isVoiceRecord: Bool { recordType == .voiceTutor }
    /// Preserved question text/draft from a previous account or API origin.
    /// It has no canonical server ID in the current account.
    var isDetachedLocalQuestion: Bool { isQuestion && id.hasPrefix("local-draft:") }

    /// Pending questions keep their existing workflow; voice is never a draft.
    var isPendingQuestion: Bool {
        isQuestion && !isDetachedLocalQuestion && gradingResult == nil &&
            questionStatus != .skipped && questionStatus != .graded && questionStatus != .completed
    }

    var isCompletedRecord: Bool {
        isVoiceRecord
            ? voiceRecord != nil && questionStatus == .completed
            : gradingResult != nil
    }

    var canPublish: Bool {
        guard isCompletedRecord else { return false }
        guard isVoiceRecord else { return true }
        return !question.question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            answer?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    var displayScore: Int? {
        isVoiceRecord ? voiceRecord?.displayScore(answer: answer) : gradingResult?.score
    }

    var translationPending: Bool {
        localization?.containsPendingTranslation == true || voiceRecord?.translationPending == true
    }

    var hasTranslatedContent: Bool {
        localization?.containsTranslation == true || voiceRecord?.containsTranslation == true
    }
}

extension CommunityQuestion {
    var isCompletedRecord: Bool {
        recordType == .voiceTutor
            ? voiceRecord != nil && status.caseInsensitiveCompare("completed") == .orderedSame
            : status.caseInsensitiveCompare("graded") == .orderedSame
    }

    var canPublish: Bool {
        guard isCompletedRecord else { return false }
        guard recordType == .voiceTutor else { return true }
        return !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            answer?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    var displayScore: Int? {
        recordType == .voiceTutor ? voiceRecord?.displayScore(answer: answer) : gradingResult?.score
    }

    var translationPending: Bool {
        localization?.containsPendingTranslation == true || voiceRecord?.translationPending == true
    }

    var hasTranslatedContent: Bool {
        localization?.containsTranslation == true || voiceRecord?.containsTranslation == true
    }
}
