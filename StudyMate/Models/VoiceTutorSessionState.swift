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

/// Safe operation metadata from the authenticated control stream. Arguments,
/// results, transcripts, and provider error text never enter this display state.
struct VoiceTutorOperationEvent: Equatable, Sendable {
    enum Phase: String, CaseIterable, Sendable {
        case started, completed, failed
    }

    let sequence: Int64
    let operationID: String
    let name: String
    let phase: Phase
    let elapsedMilliseconds: Int64

    var isValid: Bool {
        sequence > 0 && elapsedMilliseconds >= 0 && elapsedMilliseconds <= 3_600_000
            && Self.isSafeIdentifier(operationID, maximumLength: 191)
            && !name.isEmpty && name.utf8.count <= 64
            && name.utf8.first.map({ (97...122).contains($0) }) == true
            && name.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 95 }
    }

    private static func isSafeIdentifier(_ value: String, maximumLength: Int) -> Bool {
        !value.isEmpty && value.utf8.count <= maximumLength && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 95 || $0 == 45
        }
    }
}

/// One bounded state per call attempt. The live clock uses uptime so changing
/// the wall clock or returning from another app cannot reset an operation timer.
struct VoiceTutorOperationState: Equatable, Sendable {
    struct Entry: Equatable, Sendable, Identifiable {
        let event: VoiceTutorOperationEvent
        let receivedAt: TimeInterval
        var id: String { event.operationID }

        func elapsedMilliseconds(at uptime: TimeInterval) -> Int64 {
            guard event.phase == .started else { return event.elapsedMilliseconds }
            let duration = min(3_600, max(0, uptime - receivedAt))
            return event.elapsedMilliseconds + Int64(duration * 1_000)
        }
    }

    static let maximumActiveOperations = 8
    static let maximumFinishedOperations = 512
    private(set) var active: [Entry] = []
    private(set) var finished: [Entry] = []
    var latestFinished: Entry? { finished.last }
    private var latestSequence: Int64 = 0
    private var isClosed = false

    @discardableResult
    mutating func apply(_ event: VoiceTutorOperationEvent, at uptime: TimeInterval) -> Bool {
        guard !isClosed, event.isValid, uptime.isFinite, event.sequence > latestSequence else { return false }
        latestSequence = event.sequence
        if let existing = active.first(where: { $0.id == event.operationID }) {
            guard existing.event.name == event.name else { return false }
            // Duplicate starts must not reset the elapsed clock.
            guard event.phase != .started else { return false }
        } else if finished.contains(where: { $0.id == event.operationID }) {
            return false
        }
        let entry = Entry(event: event, receivedAt: active.first(where: { $0.id == event.operationID })?.receivedAt ?? uptime)
        if event.phase == .started {
            guard active.count < Self.maximumActiveOperations else { return false }
            active.append(entry)
        } else {
            active.removeAll { $0.id == event.operationID }
            finished.append(entry)
            if finished.count > Self.maximumFinishedOperations { finished.removeFirst(finished.count - Self.maximumFinishedOperations) }
        }
        return true
    }

    func visibleEntries(at uptime: TimeInterval) -> [Entry] {
        (finished + active).sorted { $0.receivedAt < $1.receivedAt }
    }

    mutating func endLocally() {
        isClosed = true
        active = []
    }
}
#endif
