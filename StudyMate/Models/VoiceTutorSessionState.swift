#if os(iOS)
import Foundation

/// A display snapshot published by the authenticated call controller. It cannot
/// submit a draft, open the microphone, pause metering, or change the call owner.
struct VoiceTutorSessionStateEvent: Equatable, Sendable {
    enum Phase: String, CaseIterable, Equatable, Sendable {
        case conversation
        case questionLoading = "question_loading"
        case questionGenerating = "question_generating"
        case questionReady = "question_ready"
        case questionReading = "question_reading"
        case answering
        case answerFinalizing = "answer_finalizing"
        case answerReview = "answer_review"
        case answerSubmitting = "answer_submitting"
        case grading, graded, ending, ended
        case questionFailed = "question_failed"
        case gradingFailed = "grading_failed"
        case answerFailed = "answer_failed"
        case failed

        var requiresAnswer: Bool {
            [.answering, .answerFinalizing, .answerReview, .answerSubmitting, .answerFailed].contains(self)
        }

        var requiresQuestion: Bool {
            requiresAnswer || [.questionReady, .questionReading, .grading, .graded, .gradingFailed].contains(self)
        }

        var isTerminal: Bool { self == .ended || self == .failed }
    }

    let sequence: Int64
    let phase: Phase
    let paused: Bool
    let revision: Int64
    let studyID: Int?
    let recordID: String?
    let answerID: String?

    init(sequence: Int64, phase: Phase, paused: Bool, revision: Int64,
         studyID: Int? = nil, recordID: String? = nil, answerID: String? = nil) {
        self.sequence = sequence
        self.phase = phase
        self.paused = paused
        self.revision = revision
        self.studyID = studyID
        self.recordID = recordID
        self.answerID = answerID
    }

    var isValid: Bool {
        guard sequence > 0, revision >= 0, studyID.map({ $0 > 0 }) ?? true else { return false }
        if let recordID {
            guard let studyID, VoiceTutorQuestionChange(studyID: studyID, recordID: recordID) != nil else { return false }
        }
        if let answerID {
            guard recordID != nil, let uuid = UUID(uuidString: answerID),
                  uuid.uuidString.lowercased() == answerID else { return false }
        }
        return (!phase.requiresQuestion || recordID != nil) && (!phase.requiresAnswer || answerID != nil)
    }
}

/// Scoped to one connection attempt. Older snapshots cannot rewind a lesson or
/// resurrect a completed call; pause is orthogonal to the retained lesson phase.
struct VoiceTutorSessionState: Equatable, Sendable {
    private(set) var snapshot: VoiceTutorSessionStateEvent?
    private var isClosed = false

    @discardableResult
    mutating func apply(_ event: VoiceTutorSessionStateEvent, minimumRevision: Int64 = 0) -> Bool {
        guard !isClosed, event.isValid, event.revision >= minimumRevision,
              event.sequence > (snapshot?.sequence ?? 0),
              event.revision >= (snapshot?.revision ?? 0) else { return false }
        if snapshot?.phase == .ending, event.phase != .ending, !event.phase.isTerminal { return false }
        snapshot = event
        isClosed = event.phase.isTerminal
        return true
    }

    mutating func endLocally() {
        isClosed = true
        snapshot = nil
    }
}
#endif
