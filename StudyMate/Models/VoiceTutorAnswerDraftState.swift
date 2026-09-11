#if os(iOS)
import Foundation

struct VoiceTutorAnswerStateEvent: Equatable, Sendable {
    let answerID: String
    let studyID: Int
    let recordID: String
    let revision: Int64
    let phase: VoiceTutorAnswerDraftState.Phase
    let text: String?
    let code: String?
    var question: String? = nil
}

struct VoiceTutorAnswerTranscriptEvent: Equatable, Sendable {
    let answerID: String
    let recordID: String
    let itemID: String
    let sequence: Int64
    let text: String
}

struct VoiceTutorAnswerControl: Equatable, Sendable {
    enum Kind: String, Sendable {
        case finish = "buddystudy.voice.answer.finish"
        case submit = "buddystudy.voice.answer.submit"
        case skip = "buddystudy.voice.answer.skip"
        case cancel = "buddystudy.voice.answer.cancel"
    }
    let kind: Kind
    let answerID: String
    let recordID: String
    var text: String? = nil
}

/// Edits belong to the learner. Final ASR parts may extend the draft, but can
/// never replace a region the learner has already changed or a stored draft.
struct VoiceTutorAnswerDraftState: Equatable, Sendable {
    enum Phase: String, Equatable, Sendable {
        case inactive, listening, finalizing, review, submitting, submitted, failed, cancelled
    }
    static let maximumTextLength = 8_000
    static let maximumQuestionLength = 8_000
    private(set) var phase: Phase = .inactive
    private(set) var answerID: String?
    private(set) var recordID: String?
    private(set) var studyID: Int?
    private(set) var revision: Int64 = -1
    private(set) var text = ""
    private(set) var questionText = ""
    private(set) var failureCode: String?
    private(set) var pendingControl: VoiceTutorAnswerControl.Kind?
    private(set) var awaitingCancellationChoices = false
    private(set) var hasUserEdited = false
    private(set) var hadExistingDraft = false
    private(set) var sourceItemIDs: Set<String> = []
    private(set) var recognizedText = ""
    private var segments: [Int64: VoiceTutorAnswerTranscriptEvent] = [:]
    private var nextSequence: Int64 = 1
    private var retiredAnswerIDs: Set<String> = []

    var isActive: Bool { ![.inactive, .submitted, .cancelled].contains(phase) }
    var isEditable: Bool { [.listening, .review, .failed].contains(phase) }
    var holdsMicrophone: Bool { awaitingCancellationChoices || [.finalizing, .review, .submitting, .failed].contains(phase) }
    var canSubmit: Bool {
        [.review, .failed].contains(phase) && Self.isValidSubmission(text)
    }
    var canSkip: Bool { [.listening, .review, .failed].contains(phase) }
    var isCancelling: Bool { pendingControl == .cancel }
    var canCancel: Bool {
        !isCancelling && pendingControl != .skip
            && [.listening, .finalizing, .review, .failed].contains(phase)
    }
    var shouldPersistAutomatically: Bool { !hadExistingDraft || hasUserEdited }
    var hasCanonicalQuestion: Bool { Self.isValidQuestion(questionText) }

    func canCancel(in snapshot: VoiceTutorSessionStateEvent?, minimumRevision: Int64 = 0) -> Bool {
        canCancel && revision >= minimumRevision && belongsToCurrentLesson(snapshot)
    }

    static func isValidQuestion(_ value: String) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && value.utf16.count <= maximumQuestionLength
    }

    func belongsToCurrentLesson(_ snapshot: VoiceTutorSessionStateEvent?) -> Bool {
        guard hasCanonicalQuestion else { return false }
        guard let snapshot else { return true }
        guard snapshot.revision <= revision else { return false }
        if snapshot.revision < revision { return true }
        guard snapshot.studyID.map({ $0 == studyID }) ?? true,
              snapshot.recordID.map({ $0 == recordID }) ?? true,
              snapshot.answerID.map({ $0 == answerID }) ?? true else { return false }
        return ![.conversation, .questionLoading, .questionGenerating, .questionFailed,
                 .grading, .graded, .gradingFailed, .ending, .ended, .failed].contains(snapshot.phase)
    }

    static func isValidSubmission(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.utf16.count <= maximumTextLength
    }

    @discardableResult
    mutating func apply(_ event: VoiceTutorAnswerStateEvent, existingDraft: String = "", minimumRevision: Int64 = 0,
                        currentLesson: VoiceTutorSessionStateEvent? = nil) -> Bool {
        guard event.question.map(Self.isValidQuestion) ?? true else { return false }
        let wasCancelled = phase == .cancelled
        if answerID != event.answerID {
            if let currentLesson {
                guard event.revision >= currentLesson.revision else { return false }
                if event.revision == currentLesson.revision {
                    guard currentLesson.studyID.map({ $0 == event.studyID }) ?? true,
                          currentLesson.recordID.map({ $0 == event.recordID }) ?? true,
                          currentLesson.answerID.map({ $0 == event.answerID }) ?? true else { return false }
                }
            }
            guard event.phase == .listening, event.revision >= revision,
                  event.revision >= minimumRevision,
                  let question = event.question,
                  !retiredAnswerIDs.contains(event.answerID), !isActive,
                  !awaitingCancellationChoices else { return false }
            if let answerID { retiredAnswerIDs.insert(answerID) }
            guard retiredAnswerIDs.count <= 64 else { return false }
            answerID = event.answerID
            recordID = event.recordID
            studyID = event.studyID
            revision = event.revision
            questionText = question
            text = existingDraft
            hadExistingDraft = !existingDraft.isEmpty
            hasUserEdited = false
            sourceItemIDs = []
            recognizedText = ""
            segments = [:]
            nextSequence = 1
            phase = .listening
            pendingControl = nil
            awaitingCancellationChoices = false
        } else {
            guard recordID == event.recordID, studyID == event.studyID, revision == event.revision,
                  event.question.map({ $0 == questionText }) ?? true,
                  !isCancelling || event.phase == .cancelled,
                  permits(event.phase) else { return false }
            // Exact terminal receipts may still settle an old preserved draft.
            // A late review/listening/failure cannot reacquire input after the
            // authenticated lesson snapshot has advanced to another exercise.
            if ![.submitted, .cancelled].contains(event.phase) {
                guard event.revision >= minimumRevision,
                      belongsToCurrentLesson(currentLesson) else { return false }
            }
        }
        if event.phase == .review, let finalText = event.text {
            // Review is emitted after all final parts. It is only a fallback
            // for an untouched, initially empty draft; edits are authoritative.
            if !hasUserEdited && !hadExistingDraft { text = finalText }
        }
        phase = event.phase
        failureCode = event.code
        if !wasCancelled, event.phase == .cancelled, event.code == "ANSWER_CANCELLED" { awaitingCancellationChoices = true }
        if [.review, .submitted, .failed, .cancelled].contains(event.phase) { pendingControl = nil }
        return true
    }

    @discardableResult
    mutating func append(_ event: VoiceTutorAnswerTranscriptEvent) -> Bool {
        guard !isCancelling, [.listening, .finalizing].contains(phase), event.answerID == answerID,
              event.recordID == recordID, event.sequence > 0, event.sequence <= 256,
              !sourceItemIDs.contains(event.itemID), segments[event.sequence] == nil else { return false }
        sourceItemIDs.insert(event.itemID)
        segments[event.sequence] = event
        while let segment = segments[nextSequence] {
            recognizedText = Self.appending(segment.text, to: recognizedText)
            text = Self.appending(segment.text, to: text)
            nextSequence += 1
        }
        return true
    }

    @discardableResult
    mutating func edit(_ value: String) -> Bool {
        guard isEditable else { return false }
        text = value
        hasUserEdited = true
        return true
    }

    mutating func requestFinish() -> VoiceTutorAnswerControl? {
        guard phase == .listening, let answerID, let recordID else { return nil }
        phase = .finalizing
        pendingControl = .finish
        return VoiceTutorAnswerControl(kind: .finish, answerID: answerID, recordID: recordID)
    }

    mutating func requestSubmit() -> VoiceTutorAnswerControl? {
        guard canSubmit, let answerID, let recordID else { return nil }
        phase = .submitting
        pendingControl = .submit
        failureCode = nil
        return VoiceTutorAnswerControl(kind: .submit, answerID: answerID, recordID: recordID, text: text)
    }

    mutating func requestSkip() -> VoiceTutorAnswerControl? {
        guard canSkip, let answerID, let recordID else { return nil }
        phase = .finalizing
        pendingControl = .skip
        failureCode = nil
        return VoiceTutorAnswerControl(kind: .skip, answerID: answerID, recordID: recordID)
    }

    mutating func requestCancel() -> VoiceTutorAnswerControl? {
        guard canCancel, let answerID, let recordID else { return nil }
        phase = .finalizing
        pendingControl = .cancel
        failureCode = nil
        return VoiceTutorAnswerControl(kind: .cancel, answerID: answerID, recordID: recordID)
    }

    mutating func endLocally() {
        if isActive { phase = .cancelled }
        pendingControl = nil
        awaitingCancellationChoices = false
    }

    mutating func didReceiveCancellationChoices() {
        awaitingCancellationChoices = false
    }

    private func permits(_ next: Phase) -> Bool {
        switch next {
        case .inactive: false
        case .listening: phase == .listening
        case .finalizing: [.listening, .finalizing].contains(phase)
        case .review: [.listening, .finalizing, .review, .submitting, .failed].contains(phase)
        case .submitting: [.review, .submitting, .failed].contains(phase)
        case .submitted: [.submitting, .submitted].contains(phase)
        case .failed: isActive
        case .cancelled: phase != .inactive && phase != .submitted
        }
    }

    private static func appending(_ part: String, to text: String) -> String {
        guard !part.isEmpty else { return text }
        guard !text.isEmpty else { return part }
        return text + (text.hasSuffix("\n") ? "" : "\n") + part
    }
}
#endif
