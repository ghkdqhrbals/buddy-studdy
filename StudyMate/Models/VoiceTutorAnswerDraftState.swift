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
    private(set) var phase: Phase = .inactive
    private(set) var answerID: String?
    private(set) var recordID: String?
    private(set) var studyID: Int?
    private(set) var revision: Int64 = -1
    private(set) var text = ""
    private(set) var failureCode: String?
    private(set) var hasUserEdited = false
    private(set) var hadExistingDraft = false
    private(set) var sourceItemIDs: Set<String> = []
    private(set) var recognizedText = ""
    private var segments: [Int64: VoiceTutorAnswerTranscriptEvent] = [:]
    private var nextSequence: Int64 = 1
    private var retiredAnswerIDs: Set<String> = []

    var isActive: Bool { ![.inactive, .submitted, .cancelled].contains(phase) }
    var isEditable: Bool { [.listening, .review, .failed].contains(phase) }
    var holdsMicrophone: Bool { [.finalizing, .review, .submitting, .failed].contains(phase) }
    var canSubmit: Bool {
        [.review, .failed].contains(phase) && Self.isValidSubmission(text)
    }
    var canSkip: Bool { [.listening, .review, .failed].contains(phase) }
    var shouldPersistAutomatically: Bool { !hadExistingDraft || hasUserEdited }

    static func isValidSubmission(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.utf16.count <= maximumTextLength
    }

    @discardableResult
    mutating func apply(_ event: VoiceTutorAnswerStateEvent, existingDraft: String = "") -> Bool {
        if answerID != event.answerID {
            guard event.phase == .listening, event.revision >= revision,
                  !retiredAnswerIDs.contains(event.answerID), !isActive else { return false }
            if let answerID { retiredAnswerIDs.insert(answerID) }
            guard retiredAnswerIDs.count <= 64 else { return false }
            answerID = event.answerID
            recordID = event.recordID
            studyID = event.studyID
            revision = event.revision
            text = existingDraft
            hadExistingDraft = !existingDraft.isEmpty
            hasUserEdited = false
            sourceItemIDs = []
            recognizedText = ""
            segments = [:]
            nextSequence = 1
            phase = .listening
        } else {
            guard recordID == event.recordID, studyID == event.studyID, revision == event.revision,
                  permits(event.phase) else { return false }
        }
        if event.phase == .review, let finalText = event.text {
            // Review is emitted after all final parts. It is only a fallback
            // for an untouched, initially empty draft; edits are authoritative.
            if !hasUserEdited && !hadExistingDraft { text = finalText }
        }
        phase = event.phase
        failureCode = event.code
        return true
    }

    @discardableResult
    mutating func append(_ event: VoiceTutorAnswerTranscriptEvent) -> Bool {
        guard [.listening, .finalizing].contains(phase), event.answerID == answerID,
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
        return VoiceTutorAnswerControl(kind: .finish, answerID: answerID, recordID: recordID)
    }

    mutating func requestSubmit() -> VoiceTutorAnswerControl? {
        guard canSubmit, let answerID, let recordID else { return nil }
        phase = .submitting
        failureCode = nil
        return VoiceTutorAnswerControl(kind: .submit, answerID: answerID, recordID: recordID, text: text)
    }

    mutating func requestSkip() -> VoiceTutorAnswerControl? {
        guard canSkip, let answerID, let recordID else { return nil }
        phase = .finalizing
        failureCode = nil
        return VoiceTutorAnswerControl(kind: .skip, answerID: answerID, recordID: recordID)
    }

    mutating func endLocally() {
        if isActive { phase = .cancelled }
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
