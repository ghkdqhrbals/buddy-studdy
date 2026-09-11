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

/// A saved grade belongs to one authenticated call and one exact lesson epoch.
/// A record ID alone is insufficient when the account, call, or lesson changes.
struct VoiceTutorGradingResultTarget: Equatable, Sendable {
    let sessionID: String
    let attemptID: UUID
    let ownerUserID: Int64
    let revision: Int64
    let studyID: Int
    let recordID: String

    init?(snapshot: VoiceTutorSessionStateEvent?, sessionID: String, attemptID: UUID,
          ownerUserID: Int64, minimumRevision: Int64 = 0) {
        guard let snapshot, snapshot.isValid, snapshot.phase == .graded,
              snapshot.revision >= minimumRevision,
              let studyID = snapshot.studyID, let recordID = snapshot.recordID,
              !sessionID.isEmpty, ownerUserID > 0 else { return nil }
        self.sessionID = sessionID
        self.attemptID = attemptID
        self.ownerUserID = ownerUserID
        revision = snapshot.revision
        self.studyID = studyID
        self.recordID = recordID
    }

    func matches(_ snapshot: VoiceTutorSessionStateEvent?) -> Bool {
        guard let snapshot, snapshot.isValid else { return false }
        return snapshot.phase == .graded && snapshot.revision == revision
            && snapshot.studyID == studyID && snapshot.recordID == recordID
    }
}

/// Transient presentation state only. The authenticated record endpoint supplies
/// the grade; existing SettingsStore reconciliation remains the persistence path.
struct VoiceTutorGradingResultState: Equatable {
    enum Phase: Equatable { case idle, loading, ready, failed }
    enum Failure: Equatable { case unavailable, mismatchedRecord, incompleteResult, timedOut }
    struct Request: Equatable, Sendable {
        let target: VoiceTutorGradingResultTarget
        let id: UUID
    }

    private(set) var phase: Phase = .idle
    private(set) var target: VoiceTutorGradingResultTarget?
    private(set) var result: GradingResult?
    private(set) var failure: Failure?
    private var activeRequest: Request?

    func matches(_ snapshot: VoiceTutorSessionStateEvent?) -> Bool {
        target?.matches(snapshot) == true
    }

    func accepts(_ request: Request) -> Bool {
        phase == .loading && activeRequest == request && target == request.target
    }

    @discardableResult
    mutating func reconcile(snapshot: VoiceTutorSessionStateEvent?, sessionID: String,
                            attemptID: UUID, ownerUserID: Int64,
                            minimumRevision: Int64 = 0) -> Request? {
        reconcile(target: VoiceTutorGradingResultTarget(snapshot: snapshot, sessionID: sessionID,
            attemptID: attemptID, ownerUserID: ownerUserID, minimumRevision: minimumRevision))
    }

    @discardableResult
    mutating func reconcile(target next: VoiceTutorGradingResultTarget?) -> Request? {
        guard let next else { clear(); return nil }
        // Pause snapshots and duplicate completion receipts do not reload an
        // already saved grade or retry a failed request without a user action.
        guard target != next else { return nil }
        target = next
        return beginRequest(for: next)
    }

    mutating func retry() -> Request? {
        guard phase == .failed, let target else { return nil }
        return beginRequest(for: target)
    }

    @discardableResult
    mutating func resolve(_ record: StudyRecord?, for request: Request) -> Bool {
        guard accepts(request) else { return false }
        guard let record else { return fail(for: request) }
        guard record.isQuestion, record.studyID == request.target.studyID,
              record.id == request.target.recordID else {
            return fail(for: request, reason: .mismatchedRecord)
        }
        guard record.questionStatus == .graded,
              record.gradingStatus == nil || record.gradingStatus == .completed,
              let grade = record.gradingResult, (0...100).contains(grade.score),
              !grade.feedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !grade.explanation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return fail(for: request, reason: .incompleteResult)
        }
        result = grade
        failure = nil
        phase = .ready
        activeRequest = nil
        return true
    }

    @discardableResult
    mutating func fail(for request: Request, reason: Failure = .unavailable) -> Bool {
        guard accepts(request) else { return false }
        result = nil
        failure = reason
        phase = .failed
        activeRequest = nil
        return true
    }

    mutating func clear() { self = Self() }

    private mutating func beginRequest(for target: VoiceTutorGradingResultTarget) -> Request {
        let request = Request(target: target, id: UUID())
        activeRequest = request
        phase = .loading
        result = nil
        failure = nil
        return request
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

    static func isSafeIdentifier(_ value: String, maximumLength: Int) -> Bool {
        !value.isEmpty && value.utf8.count <= maximumLength && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 95 || $0 == 45
        }
    }
}

/// Optional display-only correlation sent before the unchanged operation event.
/// Older clients safely ignore this separate event. IDs never authorize actions.
struct VoiceTutorOperationContextEvent: Equatable, Sendable {
    let operationID: String
    var responseID: String? = nil
    var learnerItemID: String? = nil
    var tutorItemID: String? = nil
    var answerID: String? = nil

    var isValid: Bool {
        if let answerID, UUID(uuidString: answerID)?.uuidString.lowercased() != answerID { return false }
        return [operationID, responseID, learnerItemID, tutorItemID].compactMap { $0 }
            .allSatisfy { VoiceTutorOperationEvent.isSafeIdentifier($0, maximumLength: 191) }
    }
}

/// One bounded state per call attempt. The live clock uses uptime so changing
/// the wall clock or returning from another app cannot reset an operation timer.
struct VoiceTutorOperationState: Equatable, Sendable {
    struct Entry: Equatable, Sendable, Identifiable {
        let event: VoiceTutorOperationEvent
        let receivedAt: TimeInterval
        let startedSequence: Int64
        let context: VoiceTutorOperationContextEvent?
        let afterCaptionID: UUID?
        let fallbackResponseID: String?
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
    private var pendingContexts: [String: VoiceTutorOperationContextEvent] = [:]

    @discardableResult
    mutating func applyContext(_ context: VoiceTutorOperationContextEvent) -> Bool {
        guard !isClosed, context.isValid, pendingContexts.count < 16,
              pendingContexts[context.operationID] == nil,
              !active.contains(where: { $0.id == context.operationID }),
              !finished.contains(where: { $0.id == context.operationID }) else { return false }
        pendingContexts[context.operationID] = context
        return true
    }

    @discardableResult
    mutating func apply(_ event: VoiceTutorOperationEvent, at uptime: TimeInterval,
                        afterCaptionID: UUID? = nil, responseID: String? = nil) -> Bool {
        guard !isClosed, event.isValid, uptime.isFinite, event.sequence > latestSequence else { return false }
        latestSequence = event.sequence
        if let existing = active.first(where: { $0.id == event.operationID }) {
            guard existing.event.name == event.name else { return false }
            // Duplicate starts must not reset the elapsed clock.
            guard event.phase != .started else { return false }
        } else if finished.contains(where: { $0.id == event.operationID }) {
            return false
        }
        let previous = active.first(where: { $0.id == event.operationID })
        let entry = Entry(event: event, receivedAt: previous?.receivedAt ?? uptime,
                          startedSequence: previous?.startedSequence ?? event.sequence,
                          context: previous?.context ?? pendingContexts[event.operationID],
                          afterCaptionID: previous.map { $0.afterCaptionID } ?? afterCaptionID,
                          fallbackResponseID: previous.map { $0.fallbackResponseID } ?? responseID)
        if event.phase == .started {
            guard active.count < Self.maximumActiveOperations else { return false }
            active.append(entry)
        } else {
            active.removeAll { $0.id == event.operationID }
            finished.append(entry)
            if finished.count > Self.maximumFinishedOperations { finished.removeFirst(finished.count - Self.maximumFinishedOperations) }
        }
        pendingContexts.removeValue(forKey: event.operationID)
        return true
    }

    func visibleEntries(at uptime: TimeInterval) -> [Entry] {
        (finished + active).sorted { $0.startedSequence < $1.startedSequence }
    }

    mutating func endLocally() {
        isClosed = true
        active = []
        pendingContexts = [:]
    }
}

/// Place each operation inside its originating turn, independent of completion
/// order and newly appended captions. A provisional response can later commit
/// without moving its operations to whichever message happens to be last.
struct VoiceTutorOperationTranscriptLayout {
    private(set) var beforeCaptions: [VoiceTutorOperationState.Entry] = []
    private(set) var byCaptionID: [UUID: [VoiceTutorOperationState.Entry]] = [:]
    private(set) var afterAssistantDraft: [VoiceTutorOperationState.Entry] = []
    private(set) var byUserInputID: [String: [VoiceTutorOperationState.Entry]] = [:]
    private(set) var afterAnswerDraft: [VoiceTutorOperationState.Entry] = []

    init(entries: [VoiceTutorOperationState.Entry], captions: [VoiceTutorCaption],
         assistantResponseID: String?, hasAssistantDraft: Bool, userInputIDs: Set<String> = [],
         answerDraftID: String? = nil) {
        for entry in entries {
            if let answerID = entry.context?.answerID {
                if let caption = captions.last(where: { $0.answerID == answerID && $0.speaker == .learner }) {
                    byCaptionID[caption.id, default: []].append(entry)
                } else if answerDraftID == answerID {
                    afterAnswerDraft.append(entry)
                }
                // Typed answers may have no ASR items. An old learner item in
                // the same boundary cannot stand in for this exact answer.
                continue
            }
            if let responseID = entry.context?.responseID,
               let caption = captions.last(where: { $0.responseID == responseID }) {
                byCaptionID[caption.id, default: []].append(entry)
            } else if hasAssistantDraft, let responseID = entry.context?.responseID,
                      responseID == assistantResponseID {
                afterAssistantDraft.append(entry)
            } else if let itemID = entry.context?.learnerItemID,
                      let caption = captions.last(where: { $0.containsProviderItemID(itemID) && $0.speaker == .learner }) {
                byCaptionID[caption.id, default: []].append(entry)
            } else if let itemID = entry.context?.learnerItemID,
                      let requestID = userInputIDs.first(where: { "buddystudy-user-input-" + $0 == itemID }) {
                byUserInputID[requestID, default: []].append(entry)
            } else if entry.context?.learnerItemID == nil, let itemID = entry.context?.tutorItemID,
                      let caption = captions.last(where: { $0.containsProviderItemID(itemID) && $0.speaker == .tutor }) {
                byCaptionID[caption.id, default: []].append(entry)
            } else if let context = entry.context,
                      context.responseID != nil || context.learnerItemID != nil || context.tutorItemID != nil {
                // The exact origin can arrive late or leave the bounded window.
                // A later poll's local receipt position is not its invoking turn.
                // Retain state, but wait for that origin instead of borrowing a
                // newer caption. The compact active-work indicator stays live.
                continue
            } else if let responseID = entry.fallbackResponseID,
                      let caption = captions.last(where: { $0.responseID == responseID }) {
                byCaptionID[caption.id, default: []].append(entry)
            } else if hasAssistantDraft, let responseID = entry.fallbackResponseID,
                      responseID == assistantResponseID {
                afterAssistantDraft.append(entry)
            } else if let id = entry.afterCaptionID {
                // A bounded transcript may trim the original turn. Do not move
                // its operations beneath an unrelated, more recent message.
                if captions.contains(where: { $0.id == id }) { byCaptionID[id, default: []].append(entry) }
            } else {
                beforeCaptions.append(entry)
            }
        }
    }
}
#endif
